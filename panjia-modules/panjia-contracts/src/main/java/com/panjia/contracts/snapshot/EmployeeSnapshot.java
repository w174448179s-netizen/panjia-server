package com.panjia.contracts.snapshot;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Date;

/**
 * 员工快照 —— 跨域只读数据传输对象（Phase 1 员工域完整字段）。
 * <p>
 * 用于结佣/算薪时按业务时点冻结员工关键信息，保证历史可追溯。
 * <p>
 * 规则：
 * <ol>
 *   <li>不可变语义（只生成一次，不回写 people 域任何数据）</li>
 *   <li>不含敏感信息（无身份证号）</li>
 *   <li>不含其他域的 @Entity（CI check-event-payload 校验）</li>
 *   <li>由消费域持久化为自己的快照表（payroll → pj_payroll_employee_snapshot，
 *       commission → pj_commission_employee_snapshot）；people 域不建快照表</li>
 * </ol>
 */
@Data
public class EmployeeSnapshot implements Snapshot, Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    // ==================== 基本信息 ====================

    /** 员工 ID（雪花 ID） */
    private Long employeeId;

    /** 工号（业务唯一标识） */
    private String employeeCode;

    /** 姓名 */
    private String name;

    /** 所属部门（门店）ID = sys_dept.dept_id */
    private Long deptId;

    /** 快照时点的人员角色：AGENT / STORE_MANAGER / DIRECTOR（影响算薪公式，必须取时点值） */
    private String role;

    /** 是否兼职（true=兼职，false=全职） */
    private boolean partTime;

    // ==================== 职级快照（算薪时点冻结） ====================

    /** 职级编码：A0~A5 / S1 / S2 / DIRECTOR */
    private String levelCode;

    /** 底薪 */
    private BigDecimal baseSalary;

    /** 基础提成比例 */
    private BigDecimal commissionRate;

    /** 社保个人承担比例 */
    private BigDecimal socialInsuranceRatio;

    // ==================== 社保快照 ====================

    /** 社保基数 */
    private BigDecimal socialBaseAmount;

    /** 社保个人比例 */
    private BigDecimal personalSocialRatio;

    /** 社保公司比例 */
    private BigDecimal companySocialRatio;

    /** 公积金自缴金额 */
    private BigDecimal housingFundAmount;

    // ==================== 其他固定扣款（事实字段，V1.3 产品化修正） ====================

    /** 是否购买商业保险（主表 commercial_insurance，事实字段，不做业务规则判断） */
    private boolean commercialInsuranceEnabled;

    /** 商业保险费（月，社保表 commercial_insurance_amount，当前 21） */
    private BigDecimal commercialInsuranceAmount;

    /** 是否住宿舍（主表 dormitory_enabled，事实字段） */
    private boolean dormitoryEnabled;

    /** 宿舍管理费（月，社保表 dormitory_fee） */
    private BigDecimal dormitoryFee;

    // ==================== 招聘奖励加点上下文（师傅视角） ====================

    /** 快照时点有效合格徒弟数（>=2 年行业经验），用于招聘奖励 +N% 上限判断 */
    private int qualifiedApprenticeCount;

    // ==================== 元数据 ====================

    /** 快照业务时点（= pointInTime，如 2026-07-31） */
    private LocalDate snapshotDate;

    /** 快照生成时间（系统时间戳） */
    private Date snapshotAt;
}
