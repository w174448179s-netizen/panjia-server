package com.panjia.license.service;

import com.panjia.license.config.LicenseProperties;
import com.panjia.license.domain.HardwareFingerprint;
import com.panjia.license.enums.FingerprintStatusEnum;
import com.panjia.license.exception.FingerprintException;
import com.panjia.license.fingerprint.DockerCollector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 指纹服务。
 * 封装指纹采集、缓存、状态管理等能力，作为 DockerCollector 的上层服务。
 *
 * 铁律 7：仅双因子（hostMachineId + instanceId），禁止新增。
 */
@Service
public class FingerprintService {

    private static final Logger log = LoggerFactory.getLogger(FingerprintService.class);

    private final LicenseProperties properties;
    private final DockerCollector dockerCollector;

    /** 缓存的指纹（采集后缓存，避免重复读文件） */
    private volatile HardwareFingerprint cachedFingerprint;

    public FingerprintService(LicenseProperties properties, DockerCollector dockerCollector) {
        this.properties = properties;
        this.dockerCollector = dockerCollector;
    }

    /**
     * 获取当前机器指纹（带缓存）。
     * 首次采集后缓存，后续直接返回缓存值。
     * @return 双因子指纹
     * @throws FingerprintException 采集失败
     */
    public HardwareFingerprint getCurrentFingerprint() {
        if (cachedFingerprint != null) {
            return cachedFingerprint;
        }
        synchronized (this) {
            if (cachedFingerprint != null) {
                return cachedFingerprint;
            }
            cachedFingerprint = dockerCollector.collect();
        }
        return cachedFingerprint;
    }

    /**
     * 强制刷新指纹缓存（换机/重置场景）。
     */
    public void refreshFingerprint() {
        cachedFingerprint = null;
        log.info("[FingerprintService] 指纹缓存已清空，下次获取将重新采集");
    }

    /**
     * 获取指纹状态。
     * 实际状态由服务端心跳响应驱动，此处提供本地快速判断。
     */
    public FingerprintStatusEnum getStatus() {
        return dockerCollector.getLocalStatus();
    }

    /**
     * 计算指纹哈希。
     * 用于 JWT payload 存储与比对。
     */
    public String calculateFingerprintHash() {
        HardwareFingerprint fp = getCurrentFingerprint();
        return fp.calculateHash();
    }
}
