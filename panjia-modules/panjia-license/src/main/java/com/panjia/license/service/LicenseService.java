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
     * 激活结果载体。
     */
    class ActivateResult {
        private final String license;
        private final String token;
        private final long offlineExpireAt;
        private final String clientMode;

        public ActivateResult(String license, String token, long offlineExpireAt, String clientMode) {
            this.license = license;
            this.token = token;
            this.offlineExpireAt = offlineExpireAt;
            this.clientMode = clientMode;
        }

        public String getLicense() { return license; }
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

        public HeartbeatResult(String status, long offlineExpireAt, String clientMode) {
            this.status = status;
            this.offlineExpireAt = offlineExpireAt;
            this.clientMode = clientMode;
        }

        public String getStatus() { return status; }
        public long getOfflineExpireAt() { return offlineExpireAt; }
        public String getClientMode() { return clientMode; }
    }

    /**
     * /check 结果载体。
     */
    class CheckResult {
        private final boolean allowed;
        private final String reason;
        private final String code;

        public CheckResult(boolean allowed, String reason, String code) {
            this.allowed = allowed;
            this.reason = reason;
            this.code = code;
        }

        public boolean isAllowed() { return allowed; }
        public String getReason() { return reason; }
        public String getCode() { return code; }
    }
}
