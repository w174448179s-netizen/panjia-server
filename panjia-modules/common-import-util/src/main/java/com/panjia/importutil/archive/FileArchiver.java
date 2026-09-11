package com.panjia.importutil.archive;

/**
 * 原始文件归档 SPI（MinIO / Local 双实现）。
 * <p>
 * 只存不删：归档文件永久保留，作为审计锚点。
 * <p>
 * {@link #archive(byte[], String, String)} 写入归档，{@link #load(String)} 读回字节；
 * 上层业务不再关心底层是本地磁盘还是对象存储。
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

    /**
     * 读回归档文件的字节内容。
     * <p>
     * storagePath 即 {@link ArchiveResult#storagePath()}（local 相对路径或 minio 对象 key，
     * 由各实现自行解释）。读不到时应抛 {@link com.panjia.importutil.exception.ImportUtilException}。
     *
     * @param storagePath 归档时返回的 storagePath
     * @return 文件字节
     */
    byte[] load(String storagePath);
}

