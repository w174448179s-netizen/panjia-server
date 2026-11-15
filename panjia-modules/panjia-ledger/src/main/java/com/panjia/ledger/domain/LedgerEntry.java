package com.panjia.ledger.domain;

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
 * 部门收支录入明细。
 * <p>金额为正数表示正常发生额（支出录正数，报表按支出方向取负展示）；
 * 负数表示冲减/退款。生效期间精确到天，报表按“区间与归属月相交”整笔计入。</p>
 */
@Data
@TableName("pj_ledger_entry")
public class LedgerEntry implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long subjectId;

    private Long deptId;

    /** INCOME / EXPENSE，冗余存储便于按方向查询 */
    private String direction;

    private BigDecimal amount;

    /** 生效日期（含） */
    private LocalDate startDate;

    /** 失效日期（含） */
    private LocalDate endDate;

    private String remark;

    private Long createBy;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
