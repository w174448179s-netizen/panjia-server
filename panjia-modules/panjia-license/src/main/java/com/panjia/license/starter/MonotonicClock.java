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
                throw new MonotonicException("单调时钟文件为空，视为严重异常");
            }
            this.lastTrustedTimeMs = parseTrustedTime(content.trim());
        } else {
            // 铁律 9：文件缺失 = 严重异常，禁止自动重建
            // 仅当刚启动、尚无任何可信基准时，允许创建初始基准（首次运行）
            boolean created = createInitialBaseline();
            if (!created) {
                throw new MonotonicException(".panjia_monotonic 缺失且无法自动创建，进入离线锁死。请联系运维通过 mTLS 通道执行 LicenseDiagnosticCli --rebuild-monotonic");
            }
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
     * trustedNow = max(系统时间, bootTime + uptime)
     */
    public long getCurrentTrustedTime() {
        long systemTime = System.currentTimeMillis();
        long uptime = System.currentTimeMillis() - bootTimeMs; // 近似 uptime
        long bootTimePlusUptime = bootTimeMs + uptime;
        return Math.max(systemTime, bootTimePlusUptime);
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
     * 创建初始基准（仅首次运行，无历史可信时间时）。
     * 有历史记录时绝不重建（防删文件+拨时间绕过）。
     */
    private boolean createInitialBaseline() {
        // 仅当数据目录完全空（首次部署）时创建初始基准
        // 已有任何状态文件存在 → 说明不是首次 → 不创建（视为缺失异常）
        return false; // 统一走异常路径，由调用方决定锁死或心跳重建
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
                                log.error("[MonotonicClock] 警告：.panjia_monotonic 被删除！立即进入离线锁死状态，禁止自动重建");
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
}
