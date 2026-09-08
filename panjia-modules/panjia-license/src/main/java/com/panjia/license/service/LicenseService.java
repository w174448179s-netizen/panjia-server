package com.panjia.license.service;

import com.panjia.license.domain.HardwareFingerprint;
import com.panjia.license.domain.LicenseContent;
import com.panjia.license.enums.LicenseStatusEnum;

/**
 * License 核心服务接口。
 * 封装激活、心跳、/check 校验等核心业务能力。
 */
public interface LicenseService {

    /**
     * 首次激活。
     * POST /api/auth/activate
     * @param authCode 授权码
     * @param fingerprint 机器指纹
     * @param productVersion 产品版本
     * @return 激活结果（含 License + token + offlineExpireAt + clientMode）
     */
    ActivateResult activate(String authCode, HardwareFingerprint fingerprint, String productVersion);

    /**
     * 心跳续期。
     * POST /api/auth/heartbeat
     * @return 心跳结果（含 status + offlineExpireAt + clientMode）
     */
    HeartbeatResult heartbeat();

    /**
     * 关键操作校验。
     * POST /api/auth/check
     * @param operation 操作枚举（IMPORT/CALCULATE/EXPORT）
     * @return 校验结果（allowed + reason + code）
     */
    CheckResult check(String operation);

    /**
     * 判断当前是否允许执行指定操作。
     * 综合在线状态、缓存、离线锁死/受限模式等多重判断。
     * @param operation 操作枚举
     * @return 是否允许
     */
    boolean isOperationAllowed(String operation);

    /**
     * 当前授权状态。
     */
    LicenseStatusEnum getCurrentStatus();

    /**
     * 是否处于受限模式。
     */
    boolean isRestricted();

    /**
     * 获取当前指纹。
     */
    HardwareFingerprint getCurrentFingerprint();

    /**
     * 获取解码后的 License 内容。
     */
    LicenseContent getLicenseContent();

    /**
     * 获取当前持久化的 JWT token 字符串（未激活时为 null）。
     */
    String getToken();

    /**
     * 判断当前 License 是否已过期。
     * 综合检查 JWT exp 与 licenseExpireAt，任一过期返回 true。
     */
    boolean isTokenExpired();

    /**
     * 激活结果载体。
     */
    class ActivateResult {
        private final String token;
        private final long offlineExpireAt;
        private final String clientMode;

        public ActivateResult(String token, long offlineExpireAt, String clientMode) {
            this.token = token;
            this.offlineExpireAt = offlineExpireAt;
            this.clientMode = clientMode;
        }

        public String getToken() { return token; }
        public long getOfflineExpireAt() { return offlineExpireAt; }
        public String getClientMode() { return clientMode; }
    }

    /**
     * 心跳结果载体。
     */
    class HeartbeatResult {
        private final String status;
        private final long offlineExpireAt;
        private final String clientMode;
        private final String newToken;

        public HeartbeatResult(String status, long offlineExpireAt, String clientMode) {
            this(status, offlineExpireAt, clientMode, null);
        }

        public HeartbeatResult(String status, long offlineExpireAt, String clientMode, String newToken) {
            this.status = status;
            this.offlineExpireAt = offlineExpireAt;
            this.clientMode = clientMode;
            this.newToken = newToken;
        }

        public String getStatus() { return status; }
        public long getOfflineExpireAt() { return offlineExpireAt; }
        public String getClientMode() { return clientMode; }
        public String getNewToken() { return newToken; }
    }

    /**
     * /check 结果载体。
     * <p>
     * ★ P0-5 修复：增加 clientMode 字段，让调用方在受限模式下也能感知"当前是 RESTRICT 但放行"，
     *   业务侧根据 clientMode=RESTRICT 自行决定是否注入"算薪偏移"（V1.3 §5.1）。
     */
    class CheckResult {
        private final boolean allowed;
        private final String reason;
        private final String code;
        private final String clientMode;

        public CheckResult(boolean allowed, String reason, String code) {
            this(allowed, reason, code, "NORMAL");
        }

        public CheckResult(boolean allowed, String reason, String code, String clientMode) {
            this.allowed = allowed;
            this.reason = reason;
            this.code = code;
            this.clientMode = clientMode == null ? "NORMAL" : clientMode;
        }

        public boolean isAllowed() { return allowed; }
        public String getReason() { return reason; }
        public String getCode() { return code; }
        public String getClientMode() { return clientMode; }
        public boolean isRestricted() { return "RESTRICT".equals(clientMode); }
    }
}
