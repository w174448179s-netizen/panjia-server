package com.panjia.license.starter;

import com.panjia.license.config.LicenseProperties;
import com.panjia.license.exception.MonotonicException;
import com.panjia.license.util.LicenseFileUtils;
import com.panjia.license.util.MonotonicTolerance;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.time.Instant;

/**
 * 单调时钟（离线时间防护）。
 *
 * 漏洞背景：离线模式下客户端靠本地时间判断 offlineExpireAt，客户可把系统时间往前拨无限延长宽限期。
 * 机制：单调时钟单文件 .panjia_monotonic，记录"上次看到的可信时间"，任何回拨即拒绝。
 *
 * 时钟源（双源取 max）：
 *   - 系统时间 System.currentTimeMillis()                       → 可被改 ❌
 *   - 系统启动时刻 /proc/stat 的 btime（跨重启单调锚点）         → 改系统时间不影响 ✅
 *
 *   trustedNow = max(系统时间, bootTime + uptime)
 *
 * 铁律 8：禁止使用单一 System.currentTimeMillis()，须含 btime 跨重启锚点。
 * 铁律 9：.panjia_monotonic 缺失 = 严重异常，禁止自动重建，仅心跳/运维可恢复。
 */
@Slf4j
@Component
public class MonotonicClock {

    private final LicenseProperties properties;
    private final LicenseFileUtils fileUtils;

    /** 最近一次验证通过的可信时间（毫秒） */
    private volatile long lastTrustedTimeMs = 0;

    /** 系统启动时刻（/proc/stat btime，毫秒） */
    private volatile long bootTimeMs = 0;

    /**
     * 篡改标志：运行期单调文件被删除（WatchService 检测到）即置位。
     * 置位后 trustedNow 恒为 Long.MAX_VALUE（token 视为已过期 → 锁死），
     * verifyAndUpdate 直接抛异常，仅重启进程可清除（重启后 initialize 仍会因
     * "token 在而单调文件不在" 再次拒绝，形成双保险）。
     */
    private volatile boolean tampered = false;

    public MonotonicClock(LicenseProperties properties, LicenseFileUtils fileUtils) {
        this.properties = properties;
        this.fileUtils = fileUtils;
    }

    /**
     * 启动期调用：初始化单调时钟。
     * 顺序：① 读 btime ② 读/建单调文件 ③ 校验 ④ 注册文件删除监听
     *
     * @throws MonotonicException 文件缺失且无法恢复时抛出（进入离线锁死）
     */
    public void initialize() {
        // 1. 读取系统启动时刻
        this.bootTimeMs = readBootTime();

        // 2. 读取或创建单调文件
        String fileName = properties.getFile().getMonotonic();
        if (fileUtils.exists(fileName)) {
            String content = fileUtils.readFirstLine(fileName);
            if (content == null || content.trim().isEmpty()) {
                tampered = true;
                throw new MonotonicException("单调时钟文件为空，视为严重异常");
            }
            this.lastTrustedTimeMs = parseTrustedTime(content.trim());
        } else {
            // ★ 铁律 9（S-1 修复）：单调文件缺失时的处理分两种：
            //   a) token 文件也不存在 → 真正的首次部署（或全新数据卷）→ 允许建立初始基准；
            //   b) token 存在而单调文件缺失 → 疑似"删文件+回拨时钟"篡改 → 拒绝启动（锁死）。
            //      恢复路径：运维删除 token 文件后重新激活，或通过诊断通道重建。
            boolean tokenExists = fileUtils.exists(properties.getFile().getToken());
            if (tokenExists) {
                // 先置篡改标志再抛：即使上层 catch 了异常（如 HeartbeatScheduler 只记日志），
                // trustedNow 也已恒为极值，token 判过期 → 整体锁死，攻击链失效
                tampered = true;
                log.error("[MonotonicClock] 单调时钟文件缺失但 token 文件存在，疑似篡改（删文件+回拨时钟），进入锁死");
                throw new MonotonicException(
                        ".panjia_monotonic 缺失但 .panjia_token 存在（铁律 9）。疑似时钟篡改，"
                                + "请联系服务商运维：删除 .panjia_token 后重新激活，或执行重建流程");
            }
            long trustedNow = getCurrentTrustedTime();
            this.lastTrustedTimeMs = trustedNow;
            writeBaseline(trustedNow);
            log.warn("[MonotonicClock] 单调时钟文件缺失（首次部署），已建立初始基准: {}",
                    Instant.ofEpochMilli(trustedNow));
        }

        // 3. 注册文件删除监听（防运行时被删）
        registerDeletionWatch();

        log.info("[MonotonicClock] 初始化完成，bootTime={}, lastTrusted={}",
                Instant.ofEpochMilli(bootTimeMs), Instant.ofEpochMilli(lastTrustedTimeMs));
    }

    /**
     * 校验当前时间是否可信。
     * 调用时机：每次启动、每次心跳成功后的时间基准刷新。
     *
     * @return true = 时间可信；false = 回拨超容忍，应进入离线锁死
     * @throws MonotonicException 文件被删等异常
     */
    public boolean verifyAndUpdate() {
        // ★ S-1 修复：篡改状态下直接拒绝，任何后续时间判断不再有意义
        if (tampered) {
            throw new MonotonicException("单调时钟文件已被删除（运行期检测到），疑似篡改，离线锁死");
        }
        long trustedNow = getCurrentTrustedTime();
        long last = lastTrustedTimeMs;

        if (trustedNow < last) {
            // 时间回拨
            long backwardMs = last - trustedNow;
            if (backwardMs > MonotonicTolerance.MONOTONIC_TOLERANCE_MS) {
                // 超容忍 → 回拨 → 离线锁死
                log.warn("[MonotonicClock] 时间回拨超容忍: backward={}ms > tolerance={}ms, 触发离线锁死",
                        backwardMs, MonotonicTolerance.MONOTONIC_TOLERANCE_MS);
                throw new MonotonicException("时间回拨超容忍（" + (backwardMs / 1000) + "s > "
                        + (MonotonicTolerance.MONOTONIC_TOLERANCE_MS / 1000) + "s），离线锁死");
            } else {
                // 在容忍内 → 不更新（NTP 微调），不误杀
                log.debug("[MonotonicClock] 时间微调在容忍内，不更新基准");
                return true;
            }
        } else {
            // 时间前进 → 更新基准
            lastTrustedTimeMs = trustedNow;
            writeBaseline(trustedNow);
            return true;
        }
    }

    /**
     * 获取当前可信时间。
     * trustedNow = max(系统时间, bootTime + 真实uptime)
     * <p>
     * P1-C 修复：原实现 uptime = System.currentTimeMillis() - bootTimeMs，
     * 数学上恒等于系统时间（双源退化为单源），回拨系统时钟即可绕过。
     * 现改读 /proc/uptime（内核维护，不受用户态改时间影响），
     * bootTime + uptime 得到"真实的墙上时间"，回拨后仍能算出真实时刻。
     */
    public long getCurrentTrustedTime() {
        // ★ S-1 修复：篡改标志置位后返回极值 → token 视为已过期 → 整体锁死
        if (tampered) {
            return Long.MAX_VALUE;
        }
        long systemTime = System.currentTimeMillis();
        if (bootTimeMs <= 0) {
            // btime 不可读（非 Linux 容器/降级路径）→ 仅系统时间（防护弱化但不阻断）
            return systemTime;
        }
        long uptimeMs = readUptimeMs();
        if (uptimeMs < 0) {
            return systemTime;
        }
        long bootTimePlusUptime = bootTimeMs + uptimeMs;
        return Math.max(systemTime, bootTimePlusUptime);
    }

    /**
     * 读取 /proc/uptime 的真实 uptime（毫秒）。
     * /proc/uptime 由内核维护，用户态修改系统时钟不影响其计数。
     *
     * @return uptime 毫秒数；读取失败返回 -1
     */
    private long readUptimeMs() {
        try {
            Path uptimeFile = Path.of("/proc/uptime");
            if (!java.nio.file.Files.exists(uptimeFile)) {
                return -1;
            }
            String content = new String(java.nio.file.Files.readAllBytes(uptimeFile)).trim();
            // 格式："12345.67 23456.78"（第一列为 uptime 秒）
            String uptimeSeconds = content.split("\\s+")[0];
            return (long) (Double.parseDouble(uptimeSeconds) * 1000);
        } catch (Exception e) {
            return -1;
        }
    }

    /**
     * 获取当前可信时间（毫秒）。
     * 与 getCurrentTrustedTime() 等价，提供命名兼容性。
     */
    public long currentTimeMillis() {
        return getCurrentTrustedTime();
    }

    /**
     * 心跳成功后重建单调基准（仅此路径可重建，禁止自填）。
     * 由 LicenseDiagnosticCli 或心跳成功回调调用。
     */
    public void rebuildBaselineAfterHeartbeat() {
        long trustedNow = getCurrentTrustedTime();
        lastTrustedTimeMs = trustedNow;
        writeBaseline(trustedNow);
        log.info("[MonotonicClock] 心跳后重建单调基准: {}", Instant.ofEpochMilli(trustedNow));
    }

    /**
     * 读取系统启动时刻（/proc/stat btime）。
     * btime 是系统启动时的 Unix 时间戳，跨重启单调。
     * 读不到时降级为 0（System.currentTimeMillis() 仍参与 max，不阻断启动）。
     */
    private long readBootTime() {
        try {
            Path procStat = Path.of("/proc/stat");
            if (!java.nio.file.Files.exists(procStat)) {
                log.debug("[MonotonicClock] /proc/stat 不可读（容器内可能不可见），降级处理");
                return 0;
            }
            // 读取 btime 行：btime 1700000000
            String content = new String(java.nio.file.Files.readAllBytes(procStat));
            for (String line : content.split("\n")) {
                if (line.startsWith("btime")) {
                    return Long.parseLong(line.split("\\s+")[1]);
                }
            }
        } catch (Exception e) {
            log.debug("[MonotonicClock] 读取 btime 失败，降级处理: {}", e.getMessage());
        }
        // 异常处理：降级为单一系统时间（仍须防回拨，见铁律 8 备注）
        log.warn("[MonotonicClock] 无法读取 btime，降级为系统时间（仍有回拨防护，但锚点弱于预期）");
        return 0;
    }

    /**
     * 写基准文件（原子写）。
     */
    private void writeBaseline(long trustedTimeMs) {
        fileUtils.atomicWrite(properties.getFile().getMonotonic(), String.valueOf(trustedTimeMs));
    }

    /**
     * 解析已存基准时间。
     */
    private long parseTrustedTime(String content) {
        try {
            return Long.parseLong(content.trim());
        } catch (NumberFormatException e) {
            throw new MonotonicException("单调时钟文件格式异常: " + content);
        }
    }

    /**
     * 注册单调文件删除监听。
     * 文件被删 → 立即记录告警（不自动重建，铁律 9）。
     * 真实监听在 WatchService 线程中，此处启动线程。
     */
    private void registerDeletionWatch() {
        try {
            Path dir = fileUtils.getDataDir();
            WatchService watchService = dir.getFileSystem().newWatchService();
            dir.register(watchService, java.nio.file.StandardWatchEventKinds.ENTRY_DELETE);
            Thread watchThread = new Thread(() -> {
                try {
                    WatchKey key;
                    while ((key = watchService.take()) != null) {
                        for (WatchEvent<?> event : key.pollEvents()) {
                            if (event.kind() == java.nio.file.StandardWatchEventKinds.ENTRY_DELETE
                                    && event.context() != null
                                    && event.context().toString().equals(properties.getFile().getMonotonic())) {
                                // ★ S-1 修复：置篡改标志（不再是"只打日志"）。
                                // 置位后 trustedNow = Long.MAX_VALUE（token 全部判过期 → 锁死），
                                // verifyAndUpdate 抛异常（心跳持续失败）。
                                tampered = true;
                                log.error("[MonotonicClock] 警告：.panjia_monotonic 被删除！已置篡改标志，"
                                        + "受信时间恒为极值（token 判过期，整体锁死），禁止自动重建");
                            }
                        }
                        key.reset();
                    }
                } catch (InterruptedException e) {
                    log.warn("[MonotonicClock] 删除监听线程被中断: {}", e.getMessage());
                    Thread.currentThread().interrupt();
                } catch (Exception e) {
                    log.warn("[MonotonicClock] 删除监听线程异常: {}", e.getMessage());
                }
            }, "monotonic-watch");
            watchThread.setDaemon(true);
            watchThread.start();
        } catch (IOException e) {
            log.warn("[MonotonicClock] 无法注册删除监听: {}", e.getMessage());
        }
    }

    public long getLastTrustedTimeMs() {
        return lastTrustedTimeMs;
    }

    public long getBootTimeMs() {
        return bootTimeMs;
    }

    /**
     * 是否检测到运行期篡改（单调文件被删）。
     */
    public boolean isTampered() {
        return tampered;
    }
}
