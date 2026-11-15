package com.panjia.ledger.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 收支科目（树形，最多 4 级）。
 * <p>收入/支出各一棵树；仅叶子节点可录入；dataSource 非 MANUAL 的科目由系统自动取数。</p>
 */
@Data
@TableName("pj_ledger_subject")
public class LedgerSubject implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** INCOME=收入 / EXPENSE=支出 */
    private String direction;

    /** 父科目 ID；0=一级科目 */
    private Long parentId;

    private String subjectCode;

    private String subjectName;

    /** 层级 1-4，由后端按父级计算 */
    private Integer level;

    private Integer sortOrder;

    /** MANUAL=手工录入 / RECEIVED_DETAIL=实收明细自动 / PAYROLL_GROSS=工资应发自动 */
    private String dataSource;

    /** 0=启用 / 1=停用 */
    private String status;

    private String remark;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    /** 是否末级科目（非入库字段，列表时回填） */
    @TableField(exist = false)
    private Boolean leaf;

    /** 直接子科目数量（非入库字段，列表时回填） */
    @TableField(exist = false)
    private Integer childrenCount;
}
