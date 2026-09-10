package com.panjia.people.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 员工导入模板定义（对应 pj_people_import_template 表，V6.0 §1.8）。
 * <p>
 * column_json 直接存工具层 ColumnDef[] 数组（colName/field/type/required/enumValues/...），
 * 由 people 域的 TemplateResolver 实现读取并反序列化。
 */
@Data
@TableName("pj_people_import_template")
public class PeopleImportTemplate implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 模板 ID */
    @TableId(type = IdType.INPUT)
    private Long id;

    /** 模板编码（EMPLOYEE） */
    private String templateCode;

    /** 模板版本（如 V100） */
    private String templateVersion;

    /** 列定义 JSON（工具层 ColumnDef[]） */
    private String columnJson;

    /** 是否启用：1 启用 0 停用 */
    private Integer enabled;

    /** 创建时间（DB 默认填充） */
    private LocalDateTime createTime;
}
