package com.panjia.importutil.template.model;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 列定义（工具层模板列模型）。
 * <p>
 * 模板数据由业务域持久化（import 域 pj_import_template / people 域 pj_people_import_template），
 * 工具层只解析与使用，不负责存储。
 */
@Data
public class ColumnDef implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** Excel 表头名（中文，匹配键） */
    private String colName;

    /** 目标字段名（ParsedRow.values / rawValues 的 key） */
    private String field;

    /** 字段类型：STRING / INT / DECIMAL / DATE / BOOL */
    private String type;

    /** 基础必填（非业务必填） */
    private boolean required;

    /** 枚举白名单（可空） */
    private List<String> enumValues;

    /** 正则校验（可空） */
    private String pattern;

    /** 最大长度（可空） */
    private Integer maxLength;

    /** 日期格式（如 yyyy-MM-dd，type=DATE 时生效） */
    private String dateFormat;

    /**
     * 部门层级（可空）。
     * <p>
     * 标识此列为部门路径的第几级（1 表示大区、2 表示门店 …）。
     * 业务层会按 deptLevel 升序拼接所有该字段非空的列得到完整部门路径，
     * 新增部门层级只需在模板里追加一列并设置 deptLevel，无需修改代码。
     * 仅适用于 sys_dept 等树形部门字段。
     */
    private Integer deptLevel;
}
