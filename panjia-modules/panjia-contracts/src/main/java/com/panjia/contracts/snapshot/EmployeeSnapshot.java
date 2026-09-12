package com.panjia.contracts.snapshot;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 员工算薪事实快照（跨域只读类型，panjia-contracts 叶子模块）。
 * <p>
 * 员工在<b>某业务时点</b>（如工资归属月月末）的历史状态切片，由 people 域按
 * salary_fact 闭开区间 {@code [effective_date, expire_date)} 组装，供 payroll / commission
 * 等下游域在算薪、结佣冻结时直接编码取值，避免逐字段解析 String Map 的口径漂移。
 * <p>
 * 契约依据：架构设计 V1.9.2 §10.6（字段精确到字段级）/ §3.3 跨域只读类型 / ADR-020。
 * <p>
 * <b>铁律</b>：
 * <ul>
 *   <li>普通 POJO（{@link Serializable}），<b>禁止 Java record</b>（ADR-019）；</li>
 *   <li>只读 DTO，<b>禁止回写 people 域</b>；需要冻结留痕时由消费域物化到自己的快照表
 *       （payroll → {@code pj_payroll_employee_snapshot}，commission 同理，§3.4）；</li>
 *   <li>只含<b>业务事实</b>：身份 / 职级 / 参保开关 / 师徒关系；
 *       <b>不含任何算薪规则参数</b>（社保基数与比例、公积金金额、提点、兼职豁免规则等
 *       一律归 payroll 规则侧，见 §10.9）。</li>
 * </ul>
 */
@Data
public class EmployeeSnapshot implements Serializable {

    private static final long serialVersionUID = 1L;

    // ==================== 身份（事实） ====================

    /** 员工 ID（雪花 ID） */
    private Long employeeId;

    /** 工号（= sys_user.username） */
    private String employeeCode;

    /** 姓名 */
    private String employeeName;

    /** sys_user.id（无账号员工为 null，只参与算薪无登录权限，§10.7） */
    private Long userId;

    /** 所属部门 ID（组别/门店） */
    private Long deptId;

    /** 是否兼职（PARTTIME 时点事实；是否豁免社保/底薪由 payroll 规则决定） */
    private Boolean isPartTime;

    /**
     * 职位（= 岗位名 = 角色名，如 经纪人/店长/总监）。
     * <p>
     * 来自底座岗位/角色绑定（people 经 AccountPort 同步）；人员表不物化该字段，
     * V1 若底座岗位缺失可为 null，payroll 的角色判定可退化为 sys_role / levelCode。
     */
    private String position;

    // ==================== 职级（时点生效，salary_fact LEVEL） ====================

    /** 职级编码（A0~A5 / S1 / S2，取快照月所在闭开区间切片） */
    private String levelCode;

    // ==================== 社保（只有开关/标签，金额与比例在规则侧） ====================

    /** 是否参保（SOCIAL 事实） */
    private Boolean socialInsured;

    /** 是否缴公积金（HOUSING 事实；员工自缴金额不在人身上，归规则/导入） */
    private Boolean housingInsured;

    /** 是否买商业保险（COMMERCIAL 事实；21 元标准在规则侧） */
    private Boolean commercialInsured;

    /** 是否住宿舍（DORMITORY 事实；管理费金额在规则侧） */
    private Boolean dormitory;

    /**
     * 参保标签（普通 / HIGH，触发 payroll policy_rule 三级覆盖中的"标签级"规则）。
     * <p>
     * V1 people 域 8 类冻结 fact 无此承载（P0 待决策：扩 fact / 加列 / 仅全局+工号两级兜底），
     * 当前恒为 null；不影响 socialInsured 等开关事实。
     */
    private String socialTag;

    // ==================== 师徒（MENTOR 事实） ====================

    /** 师傅 employeeId（MENTOR 时点事实；无师傅为 null） */
    private Long mentorId;

    // ==================== 快照元数据 ====================

    /** 业务时点（取数所在月，约定为该月月末日） */
    private LocalDate snapshotDate;

    /** 快照生成时间（系统时间，用于消费域审计） */
    private LocalDateTime snapshottedAt;
}
