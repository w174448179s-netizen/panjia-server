package com.panjia.contracts.dto;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 绩效方案（pj_payroll_perf_scheme 的跨域只读视图）。
 * <p>
 * 员工域指派绩效方案（PERF_SCHEME 事实）时校验方案存在性与适用岗位；
 * 参数细节（rule_content）不跨域暴露，算薪口径由薪酬域规则快照承载。
 */
@Data
public class PerfSchemeDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 方案编码（唯一，PERF_SCHEME 事实存储值） */
    private String code;

    /** 方案名称（展示） */
    private String name;

    /** 方案类型 NONE/GRADE_RATE/RATE_TIER/AMOUNT_TIER/GRADE_AMOUNT */
    private String schemeType;

    /** 适用岗位 AGENT=经纪人/MANAGER=店长/ALL=不限 */
    private String applicableRole;

    /** 默认方案（员工未指派时算薪按此方案） */
    private Boolean isDefault;
}
