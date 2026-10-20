package com.panjia.people.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 合同自定义模板（对应 pj_people_contract_template 表）。
 * <p>
 * 按合同类型存储客户上传的 HTML 模板；未上传则使用系统内置默认模板。
 */
@Data
@TableName("pj_people_contract_template")
public class ContractTemplate implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 合同类型：LABOR/PARTTIME/OTHER（主键） */
    @TableId
    private String contractType;

    /** OSS 文件ID */
    private Long ossId;

    /** OSS 访问URL */
    private String url;

    /** 原始文件名 */
    private String originalName;

    /** 更新人 */
    private Long updateBy;

    /** 更新时间 */
    private LocalDateTime updateTime;
}
