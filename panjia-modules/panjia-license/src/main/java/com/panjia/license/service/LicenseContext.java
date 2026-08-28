package com.panjia.license.service;

import com.panjia.license.domain.HardwareFingerprint;
import com.panjia.license.domain.LicenseContent;
import com.panjia.license.enums.ClientModeEnum;
import com.panjia.license.enums.LicenseStatusEnum;
import lombok.Data;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * License 全局上下文。
 * 线程安全单例，持有当前 License 状态、指纹、token 等运行时信息。
 * 所有组件通过此上下文读写 License 运行时状态。
 */
@Data
@Component
public class LicenseContext {

    /** 当前授权内容（JWT payload  decoded） */
    private volatile LicenseContent licenseContent;

    /** 当前机器指纹 */
    private volatile HardwareFingerprint fingerprint;

    /** 解密后的 JWT token 字符串（用于心跳/check 复用） */
    private volatile String token;

    /** 当前授权状态 */
    private volatile LicenseStatusEnum status = LicenseStatusEnum.NORMAL;

    /** 是否处于受限模式 */
    private volatile boolean restricted = false;

    /** /check 结果缓存（操作 -> 过期时间） */
    private final Map<String, Long> checkCache = new ConcurrentHashMap<>();

    /** 最后成功心跳时间 */
    private volatile long lastHeartbeatTime = 0;

    /** 网络可达标志（§2.4） */
    private volatile boolean networkReachable = true;

    public void setLicense(LicenseContent content, String token) {
        this.licenseContent = content;
        this.token = token;
        this.status = LicenseStatusEnum.NORMAL;
        this.restricted = false;
        this.checkCache.clear();
        this.lastHeartbeatTime = System.currentTimeMillis();
    }

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

    public void setOfflineLock() {
        this.status = LicenseStatusEnum.OFFLINE_LOCK;
        this.restricted = false;
    }

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
