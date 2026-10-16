package com.panjia.backup.schedule;

import com.panjia.backup.service.BackupService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.redis.utils.RedisUtils;
import org.redisson.api.RLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.stream.Stream;

/**
 * 数据库每日定时备份任务。
 * <p>
 * 调用 {@link BackupService#createBackup()} 执行 pg_dump 备份，完成后清理超过保留天数的旧备份。
 * 通过 Redisson 分布式锁保障多实例集群下仅一个实例执行，避免重复备份。
 * <p>
 * 启用方式：在 <code>application.yml</code> 中配置
 * <pre>{@code
 * panjia:
 *   backup:
 *     schedule:
 *       enabled: true
 *       cron: 0 0 0 * * ?
 *     keep-days: 30
 * }</pre>
 * <ul>
 *   <li>开发环境默认关闭，避免本地频繁启动时误触发。</li>
 *   <li>生产环境部署时开启，随应用启动即可自动运行。</li>
 *   <li>cron 默认每天 00:00（秒 分 时 日 月 周），可按需调整。</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "panjia.backup.schedule.enabled", havingValue = "true")
public class BackupScheduleTask {

    private final BackupService backupService;

    /** 备份目录（与 BackupServiceImpl 同口径） */
    @Value("${panjia.backup.dir:./backup}")
    private String backupDir;

    /** 备份文件保留天数（默认 30 天） */
    @Value("${panjia.backup.keep-days:30}")
    private int keepDays;

    /** 分布式锁 key */
    private static final String LOCK_KEY = "panjia:backup:schedule-lock";

    /**
     * 每日定时备份。
     *
     * <p>先获取 Redisson 分布式锁，成功后调用备份服务，备份完成后按保留天数清理旧文件。
     * 任何异常仅记日志，不抛出，避免打断 Spring 调度线程。
     */
    @Scheduled(cron = "${panjia.backup.schedule.cron:0 0 0 * * ?}")
    public void dailyBackup() {
        RLock lock = RedisUtils.getClient().getLock(LOCK_KEY);
        if (!lock.tryLock()) {
            log.info("[定时备份] 锁被占用，其他实例正在执行，本实例跳过");
            return;
        }

        try {
            String fileName = backupService.createBackup();
            log.info("[定时备份] 备份完成：{}", fileName);
            cleanExpiredFiles();
        } catch (Exception e) {
            log.error("[定时备份] 备份执行失败：{}", e.getMessage(), e);
        } finally {
            if (lock.isHeldByCurrentThread()) {
                try {
                    lock.unlock();
                } catch (Exception e) {
                    log.warn("[定时备份] 释放分布式锁异常", e);
                }
            }
        }
    }

    /**
     * 按保留天数清理旧备份文件（双保险）。
     *
     * <p>BackupServiceImpl#createBackup() 内部已按 {@code panjia.backup.keep} 份数清理，
     * 本方法额外按文件修改时间做天数兜底，防止手动触发导致份数溢出或按时间语义不符的场景。
     */
    private void cleanExpiredFiles() {
        if (keepDays <= 0) {
            return;
        }
        Path dir;
        try {
            dir = Paths.get(backupDir).toAbsolutePath().normalize();
            if (!Files.isDirectory(dir)) {
                return;
            }
        } catch (Exception e) {
            log.warn("[定时备份] 备份目录解析失败：{}", backupDir, e);
            return;
        }

        long cutoff = System.currentTimeMillis() - keepDays * 24L * 60 * 60 * 1000;
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(p -> {
                String name = p.getFileName().toString();
                return Files.isRegularFile(p) && name.endsWith(".dump");
            }).forEach(p -> {
                try {
                    if (Files.getLastModifiedTime(p).toMillis() < cutoff) {
                        Files.deleteIfExists(p);
                        log.info("[定时备份] 清理过期备份：{}（保留 {} 天）", p.getFileName(), keepDays);
                    }
                } catch (IOException e) {
                    log.warn("[定时备份] 清理过期备份失败：{}", p.getFileName(), e);
                }
            });
        } catch (IOException e) {
            log.warn("[定时备份] 扫描备份目录失败", e);
        }
    }
}
