package com.panjia.backup.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 数据库备份文件视图对象（系统管理 → 备份恢复）。
 */
@Data
public class SysBackupFileVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 备份文件名（含扩展名，如 postgres_20261009_171111.dump） */
    private String fileName;

    /** 文件大小（字节） */
    private Long sizeBytes;

    /** 文件大小（可读格式，如 2.0 MB） */
    private String sizeLabel;

    /** 备份时间（文件修改时间） */
    private String backupTime;
}
