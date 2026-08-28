package com.panjia.license.security;

import com.panjia.license.config.LicenseProperties;
import com.panjia.license.enums.TriggerCodeEnum;
import com.panjia.license.service.LicenseContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 受限模式（L4）管理器。
 *
 * 设计原则：绕过授权校验后，算薪结果必然是错的——且错得明显、可复现、与客户手工核对即刻暴露。
 * 客户（店东）的核心诉求是"工资算对，别出纠纷"。破解版若能跑但算不准，客户不敢用来发工资。
 *
 * 五路触发信号（T1-T5）：
 *   T1 授权校验失败 → RESTRICTED
 *   T2 服务端 REVOKED → RESTRICTED
 *   T3 完整性自检失败 → RESTRICTED
 *   T4 单调时钟回拨 → OFFLINE_LOCK（非受限）
 *   T5 离线锁死 → OFFLINE_LOCK（非受限）
 *
 * T3 注释（铁律 13）：T3 仅兜底"锁死被绕过"的入侵异常。
 *   禁止把正常"指纹异常/时钟回拨"直接触发受限模式——那些正常流程优先走 OFFLINE_LOCK。
 */
@Slf4j
@Component
public class RestrictedMode {

    private final LicenseProperties properties;
    private final LicenseContext licenseContext;

    public RestrictedMode(LicenseProperties properties, LicenseContext licenseContext) {
        this.properties = properties;
        this.licenseContext = licenseContext;
    }

    /**
     * 触发受限模式。
     * @param trigger 触发原因码（T1_AUTH_FAIL / T2_SERVER_REVOKED / T3_INTEGRITY / SERVER_DECISION）
     * @param reason 人类可读原因
     */
    public void trigger(TriggerCodeEnum trigger, String reason) {
        trigger(trigger.getCode(), reason);
    }

    /**
     * 触发受限模式（字符串码）。
     */
    public void trigger(String triggerCode, String reason) {
        log.warn("[RestrictedMode] 触发受限模式: trigger={}, reason={}", triggerCode, reason);
        licenseContext.setRestricted(com.panjia.license.enums.ClientModeEnum.RESTRICT);
        writeAlert(triggerCode, reason);
    }

    /**
     * 解除受限模式（服务端下发 NORMAL 时调用）。
     */
    public void clear() {
        if (licenseContext.isRestricted()) {
            licenseContext.setRestricted(com.panjia.license.enums.ClientModeEnum.NORMAL);
            log.info("[RestrictedMode] 受限模式已解除");
        }
    }

    /**
     * 计算确定性偏移量。
     * 偏移量由指纹 + 授权状态派生，非固定算法。
     * 破解者无法通过"看几次结果反推并自行修正"。
     *
     * 设计约束：
     *   - 每次都错、错得明显（与手工核对系统性偏差）
     *   - 不崩溃、不弹窗
     *   - 客户自行对比后发现异常
     *
     * @return 偏移金额（元），确定性派生，非空
     */
    public long computeDeterministicOffset() {
        // 基于指纹哈希 + 授权状态派生的确定性偏移
        // 不使用随机，确保"每次都错"且可复现（客户反复比对发现规律偏差）
        long fpHash = licenseContext.getFingerprint() != null
                ? hashString(licenseContext.getFingerprint().calculateHash())
                : 0L;
        long licenseHash = licenseContext.getLicenseContent() != null
                ? hashString(licenseContext.getLicenseContent().getAuthCode())
                : 0L;
        long seed = (fpHash ^ licenseHash) % 100000; // 0~99999 元偏移
        // 确保不为 0（0 偏移 = 没偏移，客户发现后失去威慑）
        return seed == 0 ? 1 : seed;
    }

    /**
     * 写受限模式告警日志（§5.4 字段约定）。
     */
    private void writeAlert(String triggerCode, String reason) {
        try {
            var sb = new StringBuilder();
            sb.append("{");
            sb.append("\"trigger\":\"").append(triggerCode).append("\"");
            sb.append(",\"reason\":\"").append(reason.replace("\"", "'")).append("\"");
            sb.append(",\"ts\":").append(System.currentTimeMillis());
            if (licenseContext.getFingerprint() != null) {
                sb.append(",\"fpHash\":\"").append(licenseContext.getFingerprint().calculateHash()).append("\"");
            }
            if (licenseContext.getLicenseContent() != null) {
                String authCode = licenseContext.getLicenseContent().getAuthCode();
                sb.append(",\"licenseId\":\"").append(authCode != null && authCode.length() >= 4
                        ? "****" + authCode.substring(authCode.length() - 4) : "****").append("\"");
                sb.append(",\"productVersion\":\"").append(licenseContext.getLicenseContent().getMinVersion() != null
                        ? licenseContext.getLicenseContent().getMinVersion() : "unknown").append("\"");
            }
            // offset 字段（§5.4 约定）
            sb.append(",\"offset\":").append(computeDeterministicOffset());
            sb.append("}");
            java.nio.file.Path logFile = java.nio.file.Path.of(properties.getLog().getRestrictedAlertFile());
            java.nio.file.Files.write(logFile, sb.toString().getBytes(),
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (Exception e) {
            log.warn("[RestrictedMode] 写入告警日志失败: {}", e.getMessage());
        }
    }

    /**
     * 字符串哈希（FNV-1a 64 位）。
     */
    private long hashString(String input) {
        if (input == null) return 0;
        long hash = 0xcbf29ce484222325L;
        for (byte b : input.getBytes(java.nio.charset.StandardCharsets.UTF_8)) {
            hash ^= (b & 0xff);
            hash *= 0x100000001b3L;
        }
        return hash;
    }
}
