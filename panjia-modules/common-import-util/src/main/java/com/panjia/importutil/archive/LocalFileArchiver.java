package com.panjia.importutil.archive;

import com.panjia.importutil.config.ImportUtilProperties;
import com.panjia.importutil.exception.ImportUtilException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.UUID;

/**
 * 本地磁盘归档实现（MinIO 不可用时的兜底，默认 storage-type=local）。
 * <p>
 * 路径：{@code {baseDir}/{bizDir}/{yyyyMMdd}/{uuid}_{filename}}。
 * <p>
 * 自动 fallback：当配置的 {@code localBaseDir} 不存在或不可写时，自动转写到
 * {@code ${java.io.tmpdir}/panjia-import}，保证 dev 沙箱（即 macOS 没 /data）也能跑通。
 * WARN 日志会标注真实使用路径，便于运维侧发现配置漂移。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "panjia.import-util", name = "storage-type", havingValue = "local", matchIfMissing = true)
public class LocalFileArchiver implements FileArchiver {

    private final ImportUtilProperties properties;

    /** 首次解析出的可用 baseDir（懒加载，volatile + synchronized 双重检查） */
    private volatile Path resolvedBaseDir;
    /** 是否已经因 fallback 改用过非配置的 baseDir（影响错误信息提示文案） */
    private volatile boolean usingFallback;
    /** WARN 只打一次，避免日志洪水 */
    private volatile boolean warnedOnce;

    @Override
    public ArchiveResult archive(byte[] content, String originalFilename, String bizDir) {
        if (content == null || content.length == 0) {
            throw new ImportUtilException("归档失败：文件内容为空");
        }
        String day = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        String safeName = originalFilename == null ? "unnamed" : originalFilename.replaceAll("[\\\\/]", "_");
        String fileName = UUID.randomUUID().toString().replace("-", "") + "_" + safeName;
        String relative = Paths.get(bizDir, day, fileName).toString();
        Path baseDir = resolveUsableBaseDir();
        Path target = baseDir.resolve(relative);
        try {
            Files.createDirectories(target.getParent());
            Files.write(target, content);
        } catch (IOException e) {
            throw new ImportUtilException(
                "文件归档失败：" + target + "（baseDir=" + baseDir
                    + (usingFallback ? "，已自动 fallback" : "")
                    + "）— 根因：" + rootCauseMessage(e),
                e);
        }
        String hash = sha256(content);
        log.info("文件已归档: {} ({} bytes, sha256={}, baseDir={}{})",
            target, content.length, hash, baseDir, usingFallback ? " [fallback]" : "");
        return new ArchiveResult(relative, hash, content.length);
    }

    /**
     * 解析真正可用的 baseDir（懒加载，结果会被缓存）。
     * <p>
     * 解析顺序：配置的 {@code localBaseDir} → {@code ${java.io.tmpdir}/panjia-import}。
     */
    private Path resolveUsableBaseDir() {
        Path cached = resolvedBaseDir;
        if (cached != null) {
            return cached;
        }
        synchronized (this) {
            if (resolvedBaseDir != null) {
                return resolvedBaseDir;
            }
            String configuredStr = properties.getLocalBaseDir();
            Path configured = Paths.get(configuredStr);
            if (isUsable(configured)) {
                resolvedBaseDir = configured;
                return resolvedBaseDir;
            }
            // fallback 到 java.io.tmpdir/panjia-import
            Path fallback = Paths.get(
                System.getProperty("java.io.tmpdir", "/tmp"), "panjia-import");
            try {
                Files.createDirectories(fallback);
            } catch (IOException e) {
                throw new ImportUtilException(
                    "localBaseDir=" + configuredStr + " 不可用，且 fallback 到 "
                        + fallback + " 也失败：" + e.getMessage(),
                    e);
            }
            if (!warnedOnce) {
                log.warn("[LocalFileArchiver] 配置的 localBaseDir={} 不可用（目录不存在或无写权限），"
                    + "已自动 fallback 到 {}。生产环境请修复该目录或在 yml 里改回可写路径，"
                    + "否则服务重启后会落到 fallback 路径，下载历史批次的原文会断链。",
                    configuredStr, fallback);
                warnedOnce = true;
            }
            usingFallback = true;
            resolvedBaseDir = fallback;
            return resolvedBaseDir;
        }
    }

    @Override
    public byte[] load(String storagePath) {
        if (storagePath == null || storagePath.isBlank()) {
            throw new ImportUtilException("归档路径为空，无法读取");
        }
        Path base = resolveUsableBaseDir();
        Path abs = base.resolve(storagePath).normalize().toAbsolutePath();
        // 防穿越：解析后的路径必须仍在 baseDir 内
        Path baseAbs = base.toAbsolutePath().normalize();
        if (!abs.startsWith(baseAbs)) {
            throw new ImportUtilException("非法归档路径（不在 baseDir 内）: " + storagePath);
        }
        if (!Files.isRegularFile(abs)) {
            throw new ImportUtilException("归档文件不存在或已被清理: " + abs);
        }
        try {
            return Files.readAllBytes(abs);
        } catch (IOException e) {
            throw new ImportUtilException("读取归档文件失败: " + abs + " — 根因：" + rootCauseMessage(e), e);
        }
    }

    private static boolean isUsable(Path dir) {
        try {
            Files.createDirectories(dir);
            return Files.isWritable(dir);
        } catch (IOException | SecurityException e) {
            return false;
        }
    }

    private static String sha256(byte[] content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(content));
        } catch (Exception e) {
            throw new ImportUtilException("摘要计算失败", e);
        }
    }

    private static String rootCauseMessage(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        return cur.getClass().getSimpleName() + ": " + cur.getMessage();
    }
}
