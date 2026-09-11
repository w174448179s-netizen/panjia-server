package com.panjia.importutil.template.model;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 行级校验规则（模板 validation_rules.row_level 数组元素）。
 * <p>
 * 字段对齐模板表 JSONB 结构：
 * <ul>
 *   <li>{@code field} — 目标字段名（{@link ColumnDef#getField()}）</li>
 *   <li>{@code rule} — 规则表达式，目前支持：{@code not_blank}、{@code gte:N}、{@code gt:N}、{@code dict_in:DICT_TYPE}</li>
 *   <li>{@code message} — 失败提示文本</li>
 * </ul>
 * 工具层只定义契约，不负责规则的注册/解析/执行。
 * 由 {@code DefaultBasicValidator} 在校验阶段按 {@code rule} 分发到对应处理器。
 * <p>
 * 设计动机：模板表里 JSONB 已经写好了"哪些字段要按什么规则校验"，但消费方必须解析。
 * 引入本类让模板持久化层 + 工具层 + 校验层有统一契约，避免依赖反射字段名。
 */
@Data
public class RuleDef implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 目标字段名（与 ColumnDef.field 对齐） */
    private String field;

    /**
     * 规则表达式，约定格式：
     * <ul>
     *   <li>{@code not_blank} — 字段值不能为空</li>
     *   <li>{@code gte:N} — 数字字段 ≥ N</li>
     *   <li>{@code gt:N} — 数字字段 > N</li>
     *   <li>{@code dict_in:DICT_TYPE} — 字段值在字典 DICT_TYPE 的允许值内（与 ColumnDef.enumValues 配合）</li>
     * </ul>
     */
    private String rule;

    /** 失败时的提示文本（直接展示给用户） */
    private String message;
}
