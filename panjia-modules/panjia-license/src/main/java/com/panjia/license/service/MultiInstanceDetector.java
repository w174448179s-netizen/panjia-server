package com.panjia.license.service;

import com.panjia.license.config.LicenseProperties;
import com.panjia.license.exception.LicenseException;
import com.panjia.license.security.RestrictedMode;
import com.panjia.license.util.LicenseFileUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 多实例自动拉黑检测器。
 *
 * 设计 §2.7：
 *   同一 authCode 绑定多个 fingerprint 且均正常心跳 → 自动拉黑全部 + 通知运维 + 后台可手动解除
 *
 * 客户端行为（与换机旧实例逻辑一致）：
 *   1. 清理本地 token
 *   2. 进入受限模式（SERVER_REVOKED），写告警日志
 *   3. 绝不进入离线宽限期/锁死
 */
@Slf4j
@Service
public class MultiInstanceDetector {

    private final LicenseProperties properties;
    private final LicenseFileUtils fileUtils;
    private final RestrictedMode restrictedMode;

    /** 最近一次检测时间（防高频检测） */
    private volatile long lastDetectTime = 0;
    private static final long DETECT_INTERVAL_MS = 60_000L; // 1 分钟检测一次

    public MultiInstanceDetector(LicenseProperties properties,
                                  LicenseFileUtils fileUtils,
                                  RestrictedMode restrictedMode) {
        this.properties = properties;
        this.fileUtils = fileUtils;
        this.restrictedMode = restrictedMode;
    }

    /**
     * 每次请求时调用，检测多实例并发。
     * 实际多实例检测在服务端完成（心跳时服务端感知），此处提供本地协同检测。
     *
     * @return true = 检测到多实例并发（已拉黑），应进入受限模式
     */
    public boolean detectConcurrentHeartbeat() {
        long now = System.currentTimeMillis();
        if (now - lastDetectTime < DETECT_INTERVAL_MS) {
            return false; // 间隔内不重复检测
        }
        lastDetectTime = now;

        Path dataDir = fileUtils.getDataDir();
        try {
            List<Path> tokens = new ArrayList<>();
            try (var stream = Files.list(dataDir)) {
                stream.filter(p -> p.getFileName().toString().equals(properties.getFile().getToken()))
                        .forEach(tokens::add);
            }

            if (tokens.size() > 1) {
                log.warn("[MultiInstanceDetector] 检测到 {} 个并发实例 token，触发自动拉黑", tokens.size());
                onAutoBlacklist();
                return true;
            }
        } catch (IOException e) {
            log.debug("[MultiInstanceDetector] 读取数据目录失败: {}", e.getMessage());
        }
        return false;
    }

    /**
     * 自动拉黑处理（行为复用换机旧实例逻辑）。
     */
    private void onAutoBlacklist() {
        // 1. 清理本地 token
        try {
            fileUtils.delete(properties.getFile().getToken());
            log.info("[MultiInstanceDetector] 已清理本地 token");
        } catch (LicenseException e) {
            log.warn("[MultiInstanceDetector] 清理 token 失败: {}", e.getMessage());
        }

        // 2. 进入受限模式（SERVER_REVOKED），写告警日志
        restrictedMode.trigger("T2_SERVER_REVOKED", "多实例并发心跳，自动拉黑");

        // 3. 记录拉黑标记（后台可手动解除）
        try {
            Path marker = fileUtils.getDataDir().resolve(".panjia_blacklisted");
            Files.write(marker, Instant.now().toString().getBytes());
            log.info("[MultiInstanceDetector] 已写入拉黑标记，运营后台可手动解除");
        } catch (IOException e) {
            log.warn("[MultiInstanceDetector] 写拉黑标记失败: {}", e.getMessage());
        }
    }

    /**
     * 检查是否被拉黑（供客户端启动时判断）。
     * @return true = 已被拉黑
     */
    public boolean isBlacklisted() {
        return Files.exists(fileUtils.getDataDir().resolve(".panjia_blacklisted"));
    }

    /**
     * 手动解除拉黑（运营后台调用）。
     */
    public void clearBlacklist() {
        try {
            Files.deleteIfExists(fileUtils.getDataDir().resolve(".panjia_blacklisted"));
            log.info("[MultiInstanceDetector] 拉黑已手动解除");
        } catch (IOException e) {
            log.warn("[MultiInstanceDetector] 解除拉黑失败: {}", e.getMessage());
        }
    }
}
