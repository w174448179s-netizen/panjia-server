package com.panjia.importutil.archive;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 归档结果。
 */
@Data
public class ArchiveResult implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 存储相对路径（业务域落 batch.storage_path） */
    private String storagePath;

    /** 文件 SHA-256 摘要 */
    private String fileHash;

    /** 文件字节大小 */
    private long size;

    public ArchiveResult() {
    }

    public ArchiveResult(String storagePath, String fileHash, long size) {
        this.storagePath = storagePath;
        this.fileHash = fileHash;
        this.size = size;
    }
}
