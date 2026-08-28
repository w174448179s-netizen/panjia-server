package com.panjia.license.starter;

import com.panjia.license.config.LicenseProperties;
import com.panjia.license.security.LicenseGuard;
import com.panjia.license.service.LicenseService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 后台心跳调度器。
 *
 * dev 模式：不启动远程心跳调度（dev token 本地自洽）。
 * 生产模式：按 heartbeatIntervalMs 间隔定期调用授权服务器。
 */
@Slf4j
@Component
public class HeartbeatScheduler {

    private final LicenseProperties properties;
    private final LicenseService licenseService;
    private final MonotonicClock monotonicClock;
    private final LicenseGuard licenseGuard;
    private ScheduledExecutorService scheduler;

    public HeartbeatScheduler(LicenseProperties properties, LicenseService licenseService,
                               MonotonicClock monotonicClock, LicenseGuard licenseGuard) {
        this.properties = properties;
        this.licenseService = licenseService;
        this.monotonicClock = monotonicClock;
        this.licenseGuard = licenseGuard;
    }

    @PostConstruct
    public void start() {
        // LicenseGuard 始终返回 true，不再作为跳过条件
        // dev 模式跳过心跳调度
        if (properties.isDevMode()) {
            log.info("[HeartbeatScheduler] dev 模式，跳过远程心跳调度");
            return;
        }

        long interval = properties.getHeartbeatIntervalMs();
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "license-heartbeat-scheduler");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleAtFixedRate(this::doHeartbeat, interval, interval, TimeUnit.MILLISECONDS);
        log.info("[HeartbeatScheduler] 已启动，间隔={}h", interval / 3_600_000);
    }

    @PreDestroy
    public void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    private void doHeartbeat() {
        try {
            licenseService.heartbeat();
            monotonicClock.rebuildBaselineAfterHeartbeat();
        } catch (Exception e) {
            log.warn("[HeartbeatScheduler] 心跳失败（不影响运行）: {}", e.getMessage());
        }
    }
}
