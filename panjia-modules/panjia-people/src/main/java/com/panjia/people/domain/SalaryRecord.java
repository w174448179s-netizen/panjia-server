package com.panjia.people.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 员工算薪当前态物化记录（对应 pj_people_salary_record 表，一对一 employee）。
 * <p>
 * 由 salary_fact 最新切片物化，界面列表展示用，允许冗余、可全量重建。
 * 主键即 employee_id（非雪花生成，写入时指定）。
 */
@Data
@TableName("pj_people_salary_record")
public class SalaryRecord implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 员工 ID（主键，与 pj_people_employee.employee_id 一致） */
    @TableId(type = IdType.INPUT)
    private Long employeeId;

    /** 归属部门 ID */
    private Long deptId;

    /** 状态 */
    private EmployeeStatus status;

    /** 职级编码 */
    private String levelCode;

    /** 是否缴社保 */
    private Boolean socialInsured;

    /** 社保金额（自定义；null=用全局默认） */
    private BigDecimal socialFee;

    /** 是否缴公积金 */
    private Boolean housingInsured;

    /** 公积金金额（自定义；null=用全局默认） */
    private BigDecimal housingFund;

    /** 是否买商业保险 */
    private Boolean commercialInsured;

    /** 商业保险金额（自定义；null=用全局默认 21 元） */
    private BigDecimal commercialFee;

    /** 是否住宿舍 */
    private Boolean dormitory;

    /** 宿舍费金额（自定义；null=用全局默认） */
    private BigDecimal dormitoryFee;

    /** 是否兼职 */
    private Boolean isPartTime;

    /** 师傅员工 ID */
    private Long mentorEmployeeId;

    /** 最近刷新时间 */
    private LocalDateTime refreshTime;
}
