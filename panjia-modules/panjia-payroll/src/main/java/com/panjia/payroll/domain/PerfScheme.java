package com.panjia.payroll.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 绩效方案配置。
 * <p>
 * 5 类方案（scheme_type）：NONE 无绩效 / GRADE_RATE 积分绩效（等级→扣提点）/
 * RATE_TIER 业绩阶梯扣提点 / AMOUNT_TIER 业绩阶梯扣金额 / GRADE_AMOUNT 积分等级扣金额。
 * 员工经 PERF_SCHEME 事实按月指派（值为 code）；未指派按 is_default 方案（积分绩效）。
 */
@Data
@TableName("pj_payroll_perf_scheme")
public class PerfScheme implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 方案编码（唯一，PERF_SCHEME 事实存储值） */
    private String code;

    /** 方案名称（展示） */
    private String name;

    /** 方案类型 NONE/GRADE_RATE/RATE_TIER/AMOUNT_TIER/GRADE_AMOUNT */
    private String schemeType;

    /** 适用岗位 AGENT/MANAGER/ALL（员工指派时校验） */
    private String applicableRole;

    /** 方案参数 JSON（结构按 schemeType，见表注释） */
    private String ruleContent;

    /** 默认方案（员工未指派时生效） */
    private Boolean isDefault;

    private LocalDate effectiveFrom;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
