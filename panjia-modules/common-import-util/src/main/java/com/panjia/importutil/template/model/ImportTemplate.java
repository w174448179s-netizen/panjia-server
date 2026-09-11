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
}
