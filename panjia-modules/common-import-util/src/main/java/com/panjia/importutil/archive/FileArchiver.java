package com.panjia.importutil.archive;

/**
 * 原始文件归档 SPI（MinIO / Local 双实现）。
 * <p>
 * 只存不删：归档文件永久保留，作为审计锚点。
 */
public interface FileArchiver {

    /**
     * 归档文件字节内容。
     *
     * @param content          文件字节
     * @param originalFilename 原始文件名
     * @param bizDir           业务目录（如 import / people）
     * @return 归档结果（storagePath + fileHash + size）
     */
    ArchiveResult archive(byte[] content, String originalFilename, String bizDir);
}
