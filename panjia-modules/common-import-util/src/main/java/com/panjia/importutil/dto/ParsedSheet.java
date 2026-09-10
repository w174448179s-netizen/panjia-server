package com.panjia.importutil.dto;

import com.panjia.importutil.validate.FieldError;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 文件解析结果（纯内存，不含任何业务语义）。
 */
@Data
public class ParsedSheet implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 使用的模板编码 */
    private String templateCode;

    /** 模板版本（业务域批次创建时快照冻结用） */
    private String templateVersion;

    /** 原始表头 */
    private List<String> headers = new ArrayList<>();

    /** 解析后的行 */
    private List<ParsedRow> rows = new ArrayList<>();

    /** 基础格式错误（类型转换失败等，不阻断，交业务域决策） */
    private List<FieldError> errors = new ArrayList<>();

    public int getTotalRows() {
        return rows.size();
    }

    public int getErrorRows() {
        return (int) rows.stream().filter(r -> !r.isValid()).count();
    }
}
