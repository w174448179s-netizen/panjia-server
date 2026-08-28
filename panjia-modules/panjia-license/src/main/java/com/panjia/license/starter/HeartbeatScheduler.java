package com.panjia.license.starter;

import com.panjia.license.config.LicenseProperties;
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
 * 24h 周期调用 LicenseService.heartbeat()，心跳成功后续重建单调基准。
 */
@Slf4j
@Component
public class HeartbeatScheduler {

    private final LicenseProperties properties;
    private final LicenseService licenseService;
    private final MonotonicClock monotonicClock;
    private ScheduledExecutorService scheduler;

    public HeartbeatScheduler(LicenseProperties properties, LicenseService licenseService, MonotonicClock monotonicClock) {
        this.properties = properties;
        this.licenseService = licenseService;
        this.monotonicClock = monotonicClock;
    }

    @PostConstruct
    public void start() {
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
            // 铁律：仅心跳成功可重建单调基准
            monotonicClock.rebuildBaselineAfterHeartbeat();
        } catch (Exception e) {
            log.warn("[HeartbeatScheduler] 心跳失败（不影响运行）: {}", e.getMessage());
        }
    }
}
