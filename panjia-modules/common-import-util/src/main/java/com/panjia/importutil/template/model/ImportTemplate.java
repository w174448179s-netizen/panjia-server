package com.panjia.importutil.template.model;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 导入模板定义（工具层内存模型，无持久化）。
 * <p>
 * 由业务域的 {@code TemplateResolver} 实现从各自模板表加载并转换。
 */
@Data
public class ImportTemplate implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 模板编码（如 KE_SIGNED / EMPLOYEE） */
    private String templateCode;

    /** 模板版本（业务域批次创建时快照冻结） */
    private String templateVersion;

    /** 表头行号（0-based，默认 0） */
    private int headerRow = 0;

    /** 列定义 */
    private List<ColumnDef> columns;

    /** Excel sheet 名称（null 表示取第一个 sheet） */
    private String sheetName;

    /**
     * 行级校验规则（来自模板表的 validation_rules.row_level 数组）。
     * <p>
     * 注意：本字段是 V2.0 增量 — 让 {@code DefaultBasicValidator} 真正消费模板声明的规则，
     * 避免 {@code not_blank} 等规则变成 JSONB 死代码。
     */
    private List<RuleDef> validationRules;
}
