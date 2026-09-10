package com.panjia.people.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 员工导入原始行（对应 pj_people_import_raw 表，V6.0 §1.6）。
 * <p>
 * insert-only 审计锚点：只追加，禁止 UPDATE/DELETE；
 * raw_json 存工具层 ParsedRow.rawValues 全量原始字符串。
 */
@Data
@TableName("pj_people_import_raw")
public class PeopleImportRaw implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 原始行 ID，雪花 ID */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 所属批次 ID */
    private Long batchId;

    /** 数据行号（1-based） */
    private Integer rowNo;

    /** 全量原始行 JSONB（field → 原始字符串） */
    private String rawJson;

    /** 创建时间（DB 默认填充） */
    private LocalDateTime createTime;
}
