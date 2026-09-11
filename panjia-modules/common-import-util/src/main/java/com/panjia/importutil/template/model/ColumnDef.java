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

    /**
     * 枚举白名单（可空）。
     * <p>
     * 静态白名单：模板表里直接列出允许的值集合。Excel 客户端靠它生成下拉框，
     * 服务端靠它做"基础格式校验"。即使字典中心变动，enumValues 仍按表里写死的值判断。
     */
    private List<String> enumValues;

    /**
     * 字典属性（可空，与 enumValues 独立共存）。
     * <p>
     * 与 enumValues 是<b>两个独立维度</b>，不是互斥关系：
     * <ul>
     *   <li>{@code enumValues} — 静态白名单，用于客户端 Excel 下拉 + 服务端基础格式校验（防错位、防脏数据）</li>
     *   <li>{@code dictType} — 字典中心 sys_dict_data 实时值集合，用于服务端业务级校验
     *       （如人员 level 必须落在 panjia_employee_level 的 A0-S2 内）</li>
     * </ul>
     * <p>
     * 同时配置时的校验语义：<b>AND</b>——值必须同时落在 enumValues 和 dictType 对应字典值集合内，
     * 任一不过都拦截。这把「模板表不允许的错值」和「字典中心已废弃的旧值」双重过滤。
     * <p>
     * 仅配 dictType、不配 enumValues 时：纯走字典实时校验（适合字典频繁变更的字段）。
     * <p>
     * 仅配 enumValues、不配 dictType 时：纯静态校验（适合不会变的固定集合，如业务状态枚举）。
     */
    private String dictType;

    /** 正则校验（可空） */
    private String pattern;

    /** 最大长度（可空） */
    private Integer maxLength;

    /** 日期格式（如 yyyy-MM-dd，type=DATE 时生效） */
    private String dateFormat;

    /**
     * 转换规则（可空，模板 column_mapping.transform 原样透传）。
     * <p>
     * 逗号分隔多段，当前支持：
     * <ul>
     *   <li>{@code percent} — 百分数列：{@code 5.00%} / {@code 5.00} 统一落 0.05
     *       （量纲对齐下游乘法公式，如 shareRatio）。配置了 percent 的列，
     *       输入约定为百分比形式，裸小数 0.05 会被当作 5% 落成 0.0005</li>
     *   <li>{@code date_format:yyyy-MM-dd} — 日期格式</li>
     * </ul>
     */
    private String transform;

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
