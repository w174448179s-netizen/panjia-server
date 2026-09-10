package com.panjia.importdomain.domain.raw;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 积分原始归档（POINTS）。insert-only。
 */
@Data
@TableName("pj_import_raw_points")
public class RawPoints implements RawData {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long batchId;
    private Integer rowNo;
    private String rawJson;
    private LocalDateTime createTime;

    private String employeeCode;
    private LocalDate pointDate;
    private BigDecimal score;
    private Integer violationCount;
}
