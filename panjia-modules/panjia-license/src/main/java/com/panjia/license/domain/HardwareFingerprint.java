package com.panjia.license.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 机器指纹实体。
 * Docker 专用双因子：hostMachineId（主）+ instanceId（辅）。
 * 不采集 cpuId / MAC / memorySize / diskSerial（云上升配即变）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HardwareFingerprint {

    /** 主因子：挂载宿主机 /etc/machine-id */
    private String hostMachineId;

    /** 辅因子：数据卷持久化 instanceId */
    private String instanceId;

    /**
     * 计算指纹哈希。
     * 用于 JWT payload 存储与比对，避免明文传输。
     * @return SHA-256 哈希（十六进制）
     */
    public String calculateHash() {
        if (hostMachineId == null || instanceId == null) {
            return null;
        }
        try {
            byte[] hash = java.security.MessageDigest.getInstance("SHA-256")
                    .digest((hostMachineId + "|" + instanceId).getBytes());
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 不可用", e);
        }
    }
}
