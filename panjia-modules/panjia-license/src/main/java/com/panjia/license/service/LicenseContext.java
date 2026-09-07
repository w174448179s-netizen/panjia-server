package com.panjia.license.service;

import com.panjia.license.domain.HardwareFingerprint;
import com.panjia.license.domain.LicenseContent;
import com.panjia.license.enums.ClientModeEnum;
import com.panjia.license.enums.LicenseStatusEnum;
import lombok.Getter;
import lombok.Setter;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * License 全局上下文。
 * 线程安全单例，持有当前 License 状态、指纹、token 等运行时信息。
 * 所有组件通过此上下文读写 License 运行时状态。
 *
 * 敏感字段（licenseContent/fingerprint/token/status/restricted/checkCache/heartbeatFailureCount/serverUnavailableUntil）不暴露 setter，
 * 只能通过受控方法修改，防止外部代码直接重置授权状态绕过受限模式。
 */
@Getter
@Component
public class LicenseContext {

    /** 当前授权内容（JWT payload decoded），仅可通过 setLicense 设置 */
    private volatile LicenseContent licenseContent;

    /** 当前机器指纹（由 LicenseServiceImpl 在激活/token 加载时设置，非授权状态字段） */
    @Setter
    private volatile HardwareFingerprint fingerprint;

    /** 解密后的 JWT token 字符串（用于心跳/check 复用），仅可通过 setLicense 设置 */
    private volatile String token;

    /** 当前授权状态，仅可通过 setLicense/setRestricted/setOfflineLock/setOfflineGrace 变更。
     *  默认 NOT_ACTIVATED，只有成功加载 token（setLicense）后才变为 NORMAL。 */
    private volatile LicenseStatusEnum status = LicenseStatusEnum.NOT_ACTIVATED;

    /** 是否处于受限模式，仅可通过 setLicense/setRestricted/setOfflineLock/setOfflineGrace 变更 */
    private volatile boolean restricted = false;

    /** /check 结果缓存（操作 -> 过期时间） */
    private final Map<String, Long> checkCache = new ConcurrentHashMap<>();

    /** 最后成功心跳时间（心跳成功后由 LicenseServiceImpl 更新） */
    @Setter
    private volatile long lastHeartbeatTime = 0;

    /** 心跳连续失败次数（仅可通过 incrementHeartbeatFailure / resetHeartbeatFailure 修改） */
    private volatile int heartbeatFailureCount = 0;

    /** 服务器不可用短路截止时间戳（仅可通过 setServerUnavailableUntil / clearServerUnavailable 修改）。
     *  check 失败后设置，在此时间前不再发 HTTP 请求，直接走缓存/拒绝，避免每次操作等 TCP 超时。 */
    private volatile long serverUnavailableUntil = 0L;

    /** 网络可达标志（§2.4，由 LicenseServiceImpl 在心跳成功/失败后更新） */
    @Setter
    private volatile boolean networkReachable = true;

    /**
     * 受控设置：写入 license 内容并重置状态为 NORMAL。
     * 仅 LicenseServiceImpl 在激活成功 / 磁盘 token 加载成功时调用。
     */
    public void setLicense(LicenseContent content, String token) {
        this.licenseContent = content;
        this.token = token;
        this.status = LicenseStatusEnum.NORMAL;
        this.restricted = false;
        this.checkCache.clear();
        this.lastHeartbeatTime = System.currentTimeMillis();
        this.heartbeatFailureCount = 0;
    }

    /**
     * 受控更新：心跳返回的 offlineExpireAt。
     * LicenseContent 不可变，内部用 toBuilder() 生成新对象替换引用。
     * 仅 LicenseServiceImpl 在心跳成功时调用。
     */
    public void updateOfflineExpireAt(java.time.Instant offlineExpireAt) {
        if (this.licenseContent != null) {
            this.licenseContent = this.licenseContent.toBuilder()
                    .offlineExpireAt(offlineExpireAt)
                    .build();
        }
    }

    /**
     * 受控切换受限/正常模式（服务端下发 clientMode 时调用）。
     */
    public void setRestricted(ClientModeEnum mode) {
        if (mode == null) {
            return;
        }
        if (mode == ClientModeEnum.RESTRICT) {
            this.status = LicenseStatusEnum.RESTRICTED;
            this.restricted = true;
        } else if (mode == ClientModeEnum.NORMAL) {
            // 服务端解除受限
            this.status = LicenseStatusEnum.NORMAL;
            this.restricted = false;
        }
    }

    /**
     * 受控切换到离线锁死状态。
     */
    public void setOfflineLock() {
        this.status = LicenseStatusEnum.OFFLINE_LOCK;
        this.restricted = false;
    }

    /**
     * 心跳失败计数 +1（心跳失败时由 LicenseServiceImpl 调用）。
     * @return 自增后的失败次数
     */
    public int incrementHeartbeatFailure() {
        return ++this.heartbeatFailureCount;
    }

    /**
     * 重置心跳失败计数为 0（心跳成功时由 LicenseServiceImpl 调用）。
     */
    public void resetHeartbeatFailure() {
        this.heartbeatFailureCount = 0;
    }

    /**
     * 设置服务器不可用短路截止时间（check 失败时由 LicenseServiceImpl 调用）。
     * @param untilMs 截止时间戳（毫秒）
     */
    public void setServerUnavailableUntil(long untilMs) {
        this.serverUnavailableUntil = untilMs;
    }

    /**
     * 清除服务器不可用短路标记（check 成功/心跳成功时由 LicenseServiceImpl 调用）。
     */
    public void clearServerUnavailable() {
        this.serverUnavailableUntil = 0L;
    }

    /**
     * 判断当前是否处于服务器不可用短路期。
     * @return true = 短路期内，不应发 HTTP 请求
     */
    public boolean isServerUnavailable() {
        return this.serverUnavailableUntil > System.currentTimeMillis();
    }

    /**
     * 受控切换到离线宽限期状态。
     */
    public void setOfflineGrace() {
        this.status = LicenseStatusEnum.OFFLINE_GRACE;
        this.restricted = false;
    }

    /**
     * 缓存 /check 结果。
     * @param operation 操作枚举名
     * @param ttlMs 缓存有效期（毫秒）
     */
    public void cacheCheckResult(String operation, long ttlMs) {
        checkCache.put(operation, System.currentTimeMillis() + ttlMs);
    }

    /**
     * 判断 /check 缓存是否仍有效。
     * @param operation 操作枚举名
     * @return 有效返回 true
     */
    public boolean isCheckCacheValid(String operation) {
        Long expire = checkCache.get(operation);
        return expire != null && expire > System.currentTimeMillis();
    }

    /**
     * 清除指定操作的缓存。
     * @param operation 操作枚举名，为 null 时清空所有缓存
     */
    public void invalidateCheckCache(String operation) {
        if (operation == null) {
            checkCache.clear();
        } else {
            checkCache.remove(operation);
        }
    }
}
