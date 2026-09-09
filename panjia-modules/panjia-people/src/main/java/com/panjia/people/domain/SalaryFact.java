package com.panjia.people.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 算薪事实（对应 pj_people_salary_fact 表，一项一条）。
 * <p>
 * 闭开区间协议：{@code [effective_date, expire_date)}——
 * 取数条件 {@code effective_date <= :point AND (expire_date IS NULL OR :point < expire_date)}，
 * 按 effective_date 倒序取第一条。
 * <p>
 * value 一律存字符串：布尔类为 "true"/"false"，MENTOR 存师傅员工 ID，
 * LEVEL 存职级编码，STATUS 存状态码。
 */
@Data
@TableName("pj_people_salary_fact")
public class SalaryFact implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 事实 ID，雪花 ID */
    @TableId(type = IdType.ASSIGN_ID)
    private Long factId;

    /** 员工 ID */
    private Long employeeId;

    /** 事实类型（8 类枚举） */
    private FactType factType;

    /** 事实值（按 fact_type 解析） */
    private String value;

    /** 生效日期（闭） */
    private LocalDate effectiveDate;

    /** 失效日期（开，null=至今） */
    private LocalDate expireDate;

    /** 变更项标识（默认 ALL，初始化入职时为 ALL） */
    private String changeField;

    /** 创建时间（DB 默认填充） */
    private LocalDateTime createTime;
}
