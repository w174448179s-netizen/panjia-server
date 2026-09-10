package com.panjia.importutil.archive;

import com.panjia.importutil.config.ImportUtilProperties;
import com.panjia.importutil.exception.ImportUtilException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LocalFileArchiver implements FileArchiver {

    private final ImportUtilProperties properties;

    @Override
    public ArchiveResult archive(byte[] content, String originalFilename, String bizDir) {
        if (content == null || content.length == 0) {
            throw new ImportUtilException("归档失败：文件内容为空");
        }
        String day = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        String safeName = originalFilename == null ? "unnamed" : originalFilename.replaceAll("[\\\\/]", "_");
        String fileName = UUID.randomUUID().toString().replace("-", "") + "_" + safeName;
        String relative = Paths.get(bizDir, day, fileName).toString();
        Path target = Paths.get(properties.getLocalBaseDir()).resolve(relative);
        try {
            Files.createDirectories(target.getParent());
            Files.write(target, content);
        } catch (IOException e) {
            throw new ImportUtilException("文件归档失败: " + target, e);
        }
        String hash = sha256(content);
        log.info("文件已归档: {} ({} bytes, sha256={})", target, content.length, hash);
        return new ArchiveResult(relative, hash, content.length);
    }

    private String sha256(byte[] content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(content));
        } catch (Exception e) {
            throw new ImportUtilException("摘要计算失败", e);
        }
    }
}
