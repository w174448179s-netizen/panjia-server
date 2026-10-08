package com.panjia.payroll.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 门店月度算薪配置（门店 × 月份）。
 * <p>新签与结佣差额等门店级月度扣减项在此维护，算薪时直接取配置值扣减，
 * 不再用「新签团队业绩 − 结佣业绩」现算。
 */
@Data
@TableName("pj_payroll_dept_monthly_config")
public class DeptMonthlyConfig implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long deptId;
    /** 归属月 YYYY-MM */
    private String period;
    /** 新签与结佣差额（团队计薪业绩直接扣减项） */
    private BigDecimal diffAmount;
    private String remark;
    private Integer version;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
