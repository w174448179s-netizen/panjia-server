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
 * 非 prod 且 enabled=false 时不启动，prod 下强制启动。
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
        if (!licenseGuard.shouldEnforce()) {
            log.info("[HeartbeatScheduler] License 已关闭，心跳调度器不启动");
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
