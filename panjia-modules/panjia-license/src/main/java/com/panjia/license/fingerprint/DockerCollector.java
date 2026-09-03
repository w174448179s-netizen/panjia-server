package com.panjia.license.fingerprint;

import com.panjia.license.config.LicenseProperties;
import com.panjia.license.domain.HardwareFingerprint;
import com.panjia.license.enums.FingerprintStatusEnum;
import com.panjia.license.exception.FingerprintException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;

/**
 * Docker 专用机器指纹采集器。
 * 仅双因子：hostMachineId（/etc/machine-id）+ instanceId（数据卷持久化）。
 *
 * 铁律 7：禁止新增指纹因子（cpuId/MAC/memorySize/diskSerial）。
 * 原因：云服务器升降配即变，会增加误判。
 *
 * 适配约束：某些云镜像可能未生成 /etc/machine-id（容器内未挂载）。
 * 采集时需明确报错并提示部署人员挂载（铁律 14 配套）。
 */
@Slf4j
@Component
public class DockerCollector {

    private final LicenseProperties properties;

    public DockerCollector(LicenseProperties properties) {
        this.properties = properties;
    }

    /**
     * 采集当前机器指纹。
     * @return 双因子指纹
     * @throws FingerprintException 采集失败时抛出（部署环境问题）
     */
    public HardwareFingerprint collect() {
        // 1. 主因子：读取 /etc/machine-id
        String hostMachineId = readHostMachineId();
        if (hostMachineId == null || hostMachineId.trim().isEmpty()) {
            throw new FingerprintException("machine-id 未挂载，请联系部署人员检查 /etc/machine-id 挂载");
        }

        // 2. 辅因子：数据卷 instanceId（不存在则生成）
        String instanceId = readOrCreateInstanceId();

        HardwareFingerprint fingerprint = HardwareFingerprint.builder()
                .hostMachineId(hostMachineId.trim())
                .instanceId(instanceId.trim())
                .build();

        log.info("[DockerCollector] 指纹采集完成: host={}, instance={}",
                mask(fingerprint.getHostMachineId()), mask(fingerprint.getInstanceId()));
        return fingerprint;
    }

    /**
     * 读取宿主机的 /etc/machine-id。
     * Docker 部署时由 docker-compose 以 :ro 挂载。
     */
    private String readHostMachineId() {
        // Linux: /etc/machine-id
        try {
            return new String(Files.readAllBytes(Paths.get("/etc/machine-id"))).trim();
        } catch (IOException e) {
            // macOS fallback: ioreg 获取 IOPlatformUUID
            String osName = System.getProperty("os.name", "").toLowerCase();
            if (osName.contains("mac")) {
                try {
                    Process proc = Runtime.getRuntime().exec(
                        new String[]{"ioreg", "-d2", "-c", "IOPlatformExpertDevice"});
                    String output = new String(proc.getInputStream().readAllBytes());
                    for (String line : output.split("\n")) {
                        if (line.contains("IOPlatformUUID")) {
                            String[] parts = line.split("\"");
                            if (parts.length >= 4) {
                                log.info("[DockerCollector] macOS: 使用 IOPlatformUUID 作为 hostMachineId");
                                return parts[3].trim();
                            }
                        }
                    }
                } catch (Exception ex) {
                    log.warn("[DockerCollector] macOS UUID 获取失败: {}", ex.getMessage());
                }
            }
            // 容器内未挂载 → 明确报错（不做静默降级，铁律 14）
            throw new FingerprintException("无法读取 /etc/machine-id: " + e.getMessage()
                    + "。请通过部署工具挂载宿主机 machine-id 到 /etc/machine-id:ro");
        }
    }

    /**
     * 读取或创建数据卷 instanceId。
     * 文件必须位于持久化数据卷（铁律 14），重建容器不丢。
     */
    private String readOrCreateInstanceId() {
        Path path = Paths.get(properties.getDataDir(), properties.getFile().getInstanceId());
        try {
            if (Files.exists(path)) {
                return new String(Files.readAllBytes(path)).trim();
            }
            // 确保目录存在
            Files.createDirectories(path.getParent());
            // 不存在 → 生成 UUID 并持久化
            String uuid = UUID.randomUUID().toString();
            Files.writeString(path, uuid);
            log.info("[DockerCollector] 新建 instanceId: {}", mask(uuid));
            return uuid;
        } catch (IOException e) {
            throw new FingerprintException("无法读写 instanceId 文件（需数据卷权限）: " + e.getMessage());
        }
    }

    /**
     * 判断当前指纹是否已失效（换机/拉黑场景）。
     * 实际状态由服务端心跳响应驱动，此处提供本地快速判断。
     */
    public FingerprintStatusEnum getLocalStatus() {
        // 本地不持久化指纹状态，状态以服务端心跳为准
        return FingerprintStatusEnum.ACTIVE;
    }

    private String mask(String s) {
        if (s == null || s.length() < 8) return "******";
        return s.substring(0, 4) + "****" + s.substring(s.length() - 4);
    }
}
