package com.panjia.license.util;

import com.panjia.license.config.LicenseProperties;
import com.panjia.license.exception.LicenseException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

/**
 * License 状态文件读写工具。
 * 所有状态文件必须位于持久化数据卷（铁律 14）。
 * 文件缺失判定统一在此处，调用方不直接碰文件系统。
 */
@Slf4j
@Component
public class LicenseFileUtils {

    private final LicenseProperties properties;

    public LicenseFileUtils(LicenseProperties properties) {
        this.properties = properties;
    }

    /**
     * 状态文件根目录（持久化数据卷）。
     */
    public Path getDataDir() {
        return Paths.get(properties.getDataDir());
    }

    /**
     * 拼接状态文件路径。
     */
    public Path getFilePath(String fileName) {
        return getDataDir().resolve(fileName);
    }

    // ========== 原子写工具 ==========

    /**
     * 原子写入状态文件（先写临时文件再 rename，保证不半写）。
     */
    public void atomicWrite(String fileName, String content) {
        Path dir = getDataDir();
        Path target = dir.resolve(fileName);
        Path tmp = dir.resolve(fileName + ".tmp");
        try {
            Files.write(tmp, content.getBytes(), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            Files.move(tmp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new LicenseException("写入状态文件失败: " + fileName + " -> " + e.getMessage(), e);
        }
    }

    /**
     * 读取状态文件首行。
     */
    public String readFirstLine(String fileName) {
        try {
            Path path = getFilePath(fileName);
            if (!Files.exists(path)) {
                return null;
            }
            return new String(Files.readAllBytes(path)).trim();
        } catch (IOException e) {
            throw new LicenseException("读取状态文件失败: " + fileName + " -> " + e.getMessage(), e);
        }
    }

    /**
     * 读取 License 文件内容。
     * @return License 字符串（JWT 格式），文件不存在返回 null
     */
    public String readLicense() {
        return readFirstLine(properties.getFile().getLicense());
    }

    /**
     * 判断状态文件是否存在。
     */
    public boolean exists(String fileName) {
        return Files.exists(getFilePath(fileName));
    }

    /**
     * 删除状态文件。
     */
    public void delete(String fileName) {
        try {
            Files.deleteIfExists(getFilePath(fileName));
        } catch (IOException e) {
            throw new LicenseException("删除状态文件失败: " + fileName + " -> " + e.getMessage(), e);
        }
    }

    /**
     * 确保数据目录存在且有写权限。
     */
    public void ensureDataDir() {
        try {
            Files.createDirectories(getDataDir());
        } catch (IOException e) {
            throw new LicenseException("数据目录创建失败: " + getDataDir() + " -> " + e.getMessage(), e);
        }
    }
}
