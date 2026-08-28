package com.panjia.license.security;

import com.panjia.license.config.LicenseProperties;
import com.panjia.license.exception.IntegrityException;
import com.panjia.license.util.LicenseFileUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

/**
 * 完整性自检（L5）。
 *
 * 目的：防"class 文件被静态替换（绕过校验入口），系统未崩却照常算薪"。
 * 这是受限模式（L4）的前置触发保障——只有它能让"锁死被绕过却照常算薪"闭环。
 *
 * 攻击边界（§4.1，明确声明）：
 *   ✅ 防：静态替换 class 字节码（ProGuard/JDGUI 改 .class → 重新打包）
 *   ❌ 不防：整体替换 jar 包 + 伪造 panjia-checksums（需逆向 ClassFinal，威胁模型外）
 *   ❌ 不防：运行时内存 patch / 注入 Agent（需 Java Agent，兼职 IT 做不到）
 *
 * 构建顺序铁律 11：checksum 必须在 ClassFinal 加密之前生成。
 *    mvn package → ProGuard → checksum-gen → ClassFinal
 *    先加密后算 hash = 启动即全受限（硬 bug）
 */
@Slf4j
@Component
public class IntegrityChecker {

    private final LicenseProperties properties;
    private final LicenseFileUtils fileUtils;

    /** 需自检的关键 class（算薪前全量 + 启动期全量） */
    private final List<String> criticalClasses = new ArrayList<>();

    public IntegrityChecker(LicenseProperties properties, LicenseFileUtils fileUtils) {
        this.properties = properties;
        this.fileUtils = fileUtils;
        // 注册关键 class（算薪核心逻辑 + 校验逻辑）
        registerCriticalClasses();
    }

    /**
     * 启动期完整性全量校验。
     * LicenseStartupValidator 第一步调用。
     * @throws IntegrityException 校验失败 → 触发受限模式（T3）
     */
    public void checkStartup() {
        log.debug("[IntegrityChecker] 启动期全量校验");
        verifyAll();
    }

    /**
     * 算薪前校验。
     * 抽样 + 关键算薪 class 全量。
     * @throws IntegrityException 校验失败 → 触发受限模式（T3）
     */
    public void checkBeforeSalary() {
        log.debug("[IntegrityChecker] 算薪前校验：关键 class 全量");
        verifyAll();
    }

    /**
     * 注册需自检的关键 class 列表。
     * 这些 class 被静态替换即视为"破解尝试"，触发受限模式。
     */
    private void registerCriticalClasses() {
        // License 校验核心
        criticalClasses.add("com/panjia/license/crypto/verify/LicenseVerifier.class");
        criticalClasses.add("com/panjia/license/crypto/verify/KeyStore.class");
        criticalClasses.add("com/panjia/license/crypto/verify/PinnedTrustManager.class");
        criticalClasses.add("com/panjia/license/fingerprint/DockerCollector.class");
        criticalClasses.add("com/panjia/license/starter/MonotonicClock.class");
        criticalClasses.add("com/panjia/license/starter/NetworkReachableChecker.class");
        // 算薪核心（占位，实际算薪 module 的 class 路径）
        criticalClasses.add("com/panjia/salary/calculator/CommissionCalculator.class");
        criticalClasses.add("com/panjia/salary/calculator/RankCalculator.class");
        criticalClasses.add("com/panjia/salary/calculator/AttendanceDeduction.class");
    }

    /**
     * 全量校验所有关键 class 的 SHA-256。
     */
    private void verifyAll() {
        Path checksumsFile = fileUtils.getFilePath(properties.getFile().getChecksums());
        if (!Files.exists(checksumsFile)) {
            // 无 checksums 文件 → 无法校验 → 视为异常（防删文件绕过）
            throw new IntegrityException("panjia-checksums 缺失，无法完成完整性校验（疑似文件被删或打包异常）");
        }

        try {
            var stored = loadStoredChecksums(checksumsFile);
            for (String className : criticalClasses) {
                String actualHash = computeClassHash(className);
                String expectedHash = stored.get(className);
                if (expectedHash == null || !expectedHash.equals(actualHash)) {
                    log.error("[IntegrityChecker] class 校验失败: {} (expected={}, actual={})",
                            className, expectedHash, actualHash);
                    throw new IntegrityException("完整性校验失败: " + className + " 哈希不匹配");
                }
            }
            log.debug("[IntegrityChecker] 全部 {} 个关键 class 校验通过", criticalClasses.size());
        } catch (IntegrityException e) {
            throw e; // 直接上抛
        } catch (Exception e) {
            throw new IntegrityException("完整性校验异常: " + e.getMessage(), e);
        }
    }

    /**
     * 计算指定 class 的 SHA-256。
     * class 从当前类加载器的资源读取（经 ClassFinal 解密后的运行时 class）。
     */
    private String computeClassHash(String className) {
        String resourcePath = className.replace('.', '/');
        try (InputStream is = IntegrityChecker.class.getClassLoader().getResourceAsStream(resourcePath)) {
            if (is == null) {
                throw new IntegrityException("无法读取 class 资源: " + className);
            }
            byte[] data = is.readAllBytes();
            return toHex(sha256(data));
        } catch (IOException e) {
            throw new IntegrityException("读取 class 失败: " + className + " -> " + e.getMessage(), e);
        }
    }

    /**
     * 加载存储的校验和映射。
     */
    private java.util.Map<String, String> loadStoredChecksums(Path file) throws IOException {
        var map = new java.util.HashMap<String, String>();
        List<String> lines = Files.readAllLines(file);
        for (String line : lines) {
            if (line.trim().isEmpty() || line.startsWith("#")) continue;
            String[] parts = line.split("\\s+", 2);
            if (parts.length == 2) {
                map.put(parts[0], parts[1]);
            }
        }
        return map;
    }

    private byte[] sha256(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return md.digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 不可用", e);
        }
    }

    private String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
