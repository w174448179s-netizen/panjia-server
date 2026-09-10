package com.panjia.importdomain.domain.raw;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 手工录入原始归档（OTHERS/MANUAL）。insert-only。
 */
@Data
@TableName("pj_import_raw_manual")
public class RawManual implements RawData {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long batchId;
    private Integer rowNo;
    private String rawJson;
    private LocalDateTime createTime;

    private String employeeCode;
    private String itemType;
    private BigDecimal amount;
    private String reason;
}
