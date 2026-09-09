package com.panjia.contracts.constant;

import java.util.List;

/**
 * 员工算薪事实字段（pj_people_salary_fact.fact_type）冻结 8 类。
 * <p>
 * 员工域 V5.2：绑定到人身上的只有开关/标签/关系；社保基数、比例、公积金金额等
 * 客户级规则归 payroll 域规则 JSON，不在事实模型内。
 * <p>
 * 本清单是 people 域 salary_fact 写入、快照 Map key、payroll 取数的共同来源，
 * 新增事实类型须先改此处并评估 payroll 规则侧。
 */
public final class PeopleFactFields {

    /** 职级编码（A0~A5/S1/S2） */
    public static final String LEVEL = "LEVEL";

    /** 员工状态（ACTIVE/PARTTIME/LEFT/PENDING） */
    public static final String STATUS = "STATUS";

    /** 是否缴社保（true/false） */
    public static final String SOCIAL = "SOCIAL";

    /** 是否缴公积金（true/false） */
    public static final String HOUSING = "HOUSING";

    /** 是否买商业保险（true/false） */
    public static final String COMMERCIAL = "COMMERCIAL";

    /** 是否住宿舍（true/false） */
    public static final String DORMITORY = "DORMITORY";

    /** 是否兼职（true/false） */
    public static final String PARTTIME = "PARTTIME";

    /** 师傅员工 ID（NULL/空串=无师傅） */
    public static final String MENTOR = "MENTOR";

    /**
     * 8 类事实固定顺序（快照 Map 遍历/展示用）。
     */
    public static final List<String> FACT_TYPES = List.of(
        LEVEL, STATUS, SOCIAL, HOUSING, COMMERCIAL, DORMITORY, PARTTIME, MENTOR
    );

    private PeopleFactFields() {
        // 常量类，禁止实例化
    }
}
