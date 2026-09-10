package com.panjia.importdomain.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 导入模板配置（V100002 建表，V1.4 沿用）。
 * <p>
 * 同 source_type 单套激活模板，column_mapping JSONB 驱动列映射。
 */
@Data
@TableName("pj_import_template")
public class ImportTemplate implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String templateCode;
    private String templateVersion;

    @Version
    private Integer optLockVersion;

    private String templateName;
    private String sourceType;
    private String fileType;
    private String sheetName;
    private Integer headerRow;
    private Integer dataStartRow;

    /** 列映射 JSONB 数组 */
    private String columnMapping;

    /** 校验规则 JSONB */
    private String validationRules;

    private Boolean isActive;
}
