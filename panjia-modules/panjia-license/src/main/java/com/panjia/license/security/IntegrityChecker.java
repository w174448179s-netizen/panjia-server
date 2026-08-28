package com.panjia.license.security;

import com.panjia.license.LicenseMode;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 完整性自检（代码完整性校验）。
 *
 * 防御目标：防止 .class 文件被静态替换（反编译 → 改字节码 → 重新打包）。
 *
 * 双层校验和来源：
 * 1. 内嵌（首选）：classpath 资源 META-INF/panjia-checksums.txt，打包在 JAR 内，改 class 必须重新打包 JAR。
 * 2. 外部（降级）：dataDir/panjia-checksums，首次部署时生成，可被运维更新。
 *
 * 攻击边界：
 * ✅ 防：静态替换 class 字节码
 * ✅ 防：删除外部 checksums 文件（内嵌兜底）
 * ❌ 不防：整体替换 JAR + 伪造内嵌 checksums（需逆向打包过程）
 * ❌ 不防：运行时内存 patch / Java Agent 注入
 */
@Slf4j
@Component
public class IntegrityChecker {

    private final LicenseProperties properties;
    private final LicenseFileUtils fileUtils;

    /** 内嵌校验和资源路径（JAR 内） */
    private static final String EMBEDDED_CHECKSUMS = "META-INF/panjia-checksums.txt";

    /** 需自检的关键 class */
    private final List<String> criticalClasses = new ArrayList<>();

    public IntegrityChecker(LicenseProperties properties, LicenseFileUtils fileUtils) {
        this.properties = properties;
        this.fileUtils = fileUtils;
        registerCriticalClasses();
    }

    /**
     * 启动期完整性全量校验。
     * @throws IntegrityException 校验失败 → 触发受限模式
     */
    public void checkStartup() {
        log.debug("[IntegrityChecker] 启动期全量校验");
        verifyAll();
    }

    /**
     * 算薪前/关键操作前校验。
     * @throws IntegrityException 校验失败 → 触发受限模式
     */
    public void checkBeforeSalary() {
        log.debug("[IntegrityChecker] 关键操作前校验");
        verifyAll();
    }

    /**
     * 快速校验（仅校验核心 3 个 class，适用于高频调用）。
     * @return true=通过 false=失败
     */
    public boolean quickCheck() {
        try {
            Map<String, String> stored = loadChecksums();
            if (stored == null) {
                if (LicenseMode.DEV) {
                    return true; // 开发模式无 checksums，跳过
                }
                log.error("[IntegrityChecker] 生产环境 checksums 缺失，快速校验失败");
                return false;
            }
            for (String cls : List.of(
                    "com/panjia/license/LicenseMode.class",
                    "com/panjia/license/security/LicenseGuard.class",
                    "com/panjia/license/security/IntegrityChecker.class",
                    "com/panjia/license/crypto/verify/LicenseVerifier.class")) {
                if (!verifySingle(cls, stored)) {
                    return false;
                }
            }
            return true;
        } catch (Exception e) {
            log.error("[IntegrityChecker] 快速校验异常: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 注册需自检的关键 class 列表。
     * 包含：License 校验核心 + 安全守卫 + 受限模式 + 上下文。
     * 自校验：IntegrityChecker.class 自身也在列表中。
     */
    private void registerCriticalClasses() {
        // 编译时常量（最高优先：改了 DEV=false→true 就能绕过一切）
        criticalClasses.add("com/panjia/license/LicenseMode.class");

        // License 校验核心
        criticalClasses.add("com/panjia/license/crypto/verify/LicenseVerifier.class");
        criticalClasses.add("com/panjia/license/crypto/verify/KeyStore.class");
        criticalClasses.add("com/panjia/license/crypto/verify/PinnedTrustManager.class");

        // 安全守卫链
        criticalClasses.add("com/panjia/license/security/LicenseGuard.class");
        criticalClasses.add("com/panjia/license/security/IntegrityChecker.class");
        criticalClasses.add("com/panjia/license/security/RestrictedMode.class");
        criticalClasses.add("com/panjia/license/security/SalaryCheckGateway.class");

        // 指纹与时钟
        criticalClasses.add("com/panjia/license/fingerprint/DockerCollector.class");
        criticalClasses.add("com/panjia/license/starter/MonotonicClock.class");
        criticalClasses.add("com/panjia/license/starter/NetworkReachableChecker.class");

        // 服务与上下文
        criticalClasses.add("com/panjia/license/service/LicenseServiceImpl.class");
        criticalClasses.add("com/panjia/license/service/LicenseContext.class");

        // 拦截器
        criticalClasses.add("com/panjia/license/interceptor/LicenseInterceptor.class");
    }

    /**
     * 全量校验所有关键 class 的 SHA-256。
     */
    private void verifyAll() {
        Map<String, String> stored = loadChecksums();
        if (stored == null) {
            // 无校验和文件
            if (isDevEnvironment()) {
                log.info("[IntegrityChecker] 开发环境无 checksums 文件，跳过完整性校验");
                return;
            }
            throw new IntegrityException("panjia-checksums 缺失，无法完成完整性校验（疑似文件被删或打包异常）");
        }

        for (String className : criticalClasses) {
            if (!verifySingle(className, stored)) {
                log.error("[IntegrityChecker] class 校验失败: {}", className);
                throw new IntegrityException("完整性校验失败: " + className + " 哈希不匹配（疑似 class 文件被篡改）");
            }
        }
        log.debug("[IntegrityChecker] 全部 {} 个关键 class 校验通过", criticalClasses.size());
    }

    /**
     * 校验单个 class 文件。
     */
    private boolean verifySingle(String className, Map<String, String> stored) {
        String actualHash = computeClassHash(className);
        String expectedHash = stored.get(className);
        if (expectedHash == null) {
            // 校验和文件中无此 class 记录
            log.warn("[IntegrityChecker] class 无校验记录: {}", className);
            return false;
        }
        if (!expectedHash.equals(actualHash)) {
            log.error("[IntegrityChecker] 哈希不匹配: {} (expected={}, actual={})",
                    className, expectedHash, actualHash);
            return false;
        }
        return true;
    }

    /**
     * 加载校验和。
     * 优先从内嵌资源加载（JAR 内），降级到外部文件。
     * @return 校验和映射，null 表示无可用校验和文件
     */
    private Map<String, String> loadChecksums() {
        // 1. 内嵌资源（JAR 内，不可被外部修改）
        Map<String, String> embedded = loadEmbeddedChecksums();
        if (embedded != null) {
            log.debug("[IntegrityChecker] 使用内嵌校验和（JAR 内）");
            return embedded;
        }

        // 2. 外部文件（dataDir，首次部署生成）
        Path external = fileUtils.getFilePath(properties.getFile().getChecksums());
        if (Files.exists(external)) {
            try {
                log.debug("[IntegrityChecker] 使用外部校验和: {}", external);
                return loadChecksumsFromLines(Files.readAllLines(external));
            } catch (IOException e) {
                log.warn("[IntegrityChecker] 外部校验和读取失败: {}", e.getMessage());
            }
        }

        return null;
    }

    /**
     * 从 classpath 读取内嵌校验和资源。
     */
    private Map<String, String> loadEmbeddedChecksums() {
        try (InputStream is = IntegrityChecker.class.getClassLoader()
                .getResourceAsStream(EMBEDDED_CHECKSUMS)) {
            if (is == null) {
                return null;
            }
            String content = new String(is.readAllBytes());
            List<String> lines = List.of(content.split("\n"));
            return loadChecksumsFromLines(lines);
        } catch (IOException e) {
            log.warn("[IntegrityChecker] 内嵌校验和读取异常: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 解析校验和文件行（格式：classPath sha256hex）。
     */
    private Map<String, String> loadChecksumsFromLines(List<String> lines) {
        Map<String, String> map = new HashMap<>();
        for (String line : lines) {
            if (line.trim().isEmpty() || line.startsWith("#")) continue;
            String[] parts = line.split("\\s+", 2);
            if (parts.length == 2) {
                map.put(parts[0].trim(), parts[1].trim());
            }
        }
        return map;
    }

    /**
     * 计算指定 class 的 SHA-256。
     */
    private String computeClassHash(String classResourcePath) {
        try (InputStream is = IntegrityChecker.class.getClassLoader()
                .getResourceAsStream(classResourcePath)) {
            if (is == null) {
                throw new IntegrityException("无法读取 class 资源: " + classResourcePath);
            }
            byte[] data = is.readAllBytes();
            return toHex(sha256(data));
        } catch (IOException e) {
            throw new IntegrityException("读取 class 失败: " + classResourcePath + " -> " + e.getMessage(), e);
        }
    }

    private boolean isDevEnvironment() {
        return LicenseMode.DEV;
    }

    private byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
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
