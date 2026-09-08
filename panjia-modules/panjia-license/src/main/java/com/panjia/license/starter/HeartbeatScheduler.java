package com.panjia.license.starter;

import com.panjia.license.LicenseMode;
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
    private ScheduledExecutorService scheduler;

    public HeartbeatScheduler(LicenseProperties properties, LicenseService licenseService,
                               MonotonicClock monotonicClock) {
        this.properties = properties;
        this.licenseService = licenseService;
        this.monotonicClock = monotonicClock;
    }

    @PostConstruct
    public void start() {
        if (LicenseMode.DEV && !properties.isTestMode()) {
            // P0-C 运行期兜底：DEV 构建大声告警。生产构建（-P prod）LicenseMode.DEV=false，
            // 此分支被编译器消除；若生产环境出现此日志，说明发布了 dev 构建产物，必须立即回滚。
            log.error("[HeartbeatScheduler] !!! 安全告警：LicenseMode.DEV=true，License 授权校验已全部关闭 !!!"
                    + " 此构建禁止用于生产环境，请检查构建命令是否遗漏 -P prod");
            log.info("[HeartbeatScheduler] dev 模式，跳过远程心跳调度");
            return;
        }

        if (LicenseMode.DEV && properties.isTestMode()) {
            log.info("[HeartbeatScheduler] test 模式，启用远程心跳调度（连本地授权服务）");
        }

        // P1-C 修复：初始化单调时钟（读 btime / 单调基准文件 / 注册删除监听）。
        // 初始化失败不阻断启动（调度器只是监控组件），回拨防护由 verifyAndUpdate 在心跳路径兜底。
        try {
            monotonicClock.initialize();
        } catch (Exception e) {
            log.error("[HeartbeatScheduler] 单调时钟初始化失败（回拨防护降级）: {}", e.getMessage());
        }

        long interval = properties.getHeartbeatIntervalMs();
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "license-heartbeat-scheduler");
            t.setDaemon(true);
            return t;
        });
        // 初始延迟 0：启动成功后立即发一次心跳，之后按 interval 周期执行
        scheduler.scheduleAtFixedRate(this::doHeartbeat, 0, interval, TimeUnit.MILLISECONDS);
        log.info("[HeartbeatScheduler] 已启动，立即执行首次心跳，后续间隔={}h", interval / 3_600_000);
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
