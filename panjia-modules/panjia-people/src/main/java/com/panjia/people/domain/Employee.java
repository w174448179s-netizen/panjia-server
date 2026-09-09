package com.panjia.people.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 员工聚合根 —— people 域核心领域对象（对应 pj_people_employee）。
 * <p>
 * 聚合边界：Employee 是聚合根，{@link EmployeeLevel} / {@link SocialInsuranceProfile}
 * 是其组成部分；{@link MentorRelation} 是独立实体（独立生命周期）。
 * <p>
 * 🚨 铁律：Employee 聚合根不得离开 people 域。外部域只能通过
 * {@code EmployeeSnapshot}（快照）或 Long（员工 ID）引用。
 */
@Data
@TableName("pj_people_employee")
public class Employee implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 主键，雪花 ID（应用层 ASSIGN_ID 生成） */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 关联 sys_user.id（单向引用，可空=无登录账号） */
    private Long userId;

    /** 工号（业务唯一标识，导入匹配键） */
    private String employeeCode;

    /** 姓名 */
    private String name;

    /** 手机号 */
    private String phone;

    /** 身份证号（加密存储） */
    private String idCardNo;

    /** 所属门店/部门 ID = sys_dept.dept_id */
    private Long deptId;

    /** 岗位 ID = sys_post.post_id（可空） */
    private Long postId;

    /** 人员角色：AGENT / STORE_MANAGER / DIRECTOR（DB 列名 employee_role，非 role） */
    @TableField("employee_role")
    private EmployeeRoleEnum role;

    /** 兼职状态 */
    private PartTimeStatusEnum partTimeStatus;

    /** 员工状态 */
    private EmployeeStatusEnum status;

    /** 入职日期 */
    private LocalDate hireDate;

    /** 离职日期（status=RESIGNED 时有值） */
    private LocalDate resignDate;

    /** 是否缴纳社保（兼职=false） */
    private boolean socialInsuranceEnabled;

    /** 公积金自缴金额 */
    private BigDecimal housingFundAmount;

    /** 是否购买商业保险 */
    private boolean commercialInsurance;

    /** 是否住宿舍 */
    private boolean dormitoryEnabled;

    /** 备注 */
    private String remark;

    /** 创建人 login_name（应用层填充） */
    private String createdBy;

    /** 创建时间（应用层填充，无触发器） */
    private LocalDateTime createdAt;

    /** 更新人 login_name */
    private String updatedBy;

    /** 更新时间（应用层填充，无触发器） */
    private LocalDateTime updatedAt;

    /** 乐观锁版本号（MP @Version） */
    @Version
    private Integer optLockVersion;

    /** 职级历史（非表字段，由 Mapper 联查装配，按 effectiveFrom 倒序） */
    @TableField(exist = false)
    private List<EmployeeLevel> levelHistory;

    /** 当前社保档案（非表字段，由 Mapper 装配） */
    @TableField(exist = false)
    private SocialInsuranceProfile socialInsurance;

    /**
     * 变更职级 —— 关闭当前有效记录，追加一条新记录。
     *
     * @param newLevel      新职级记录（已填 levelCode/effectiveFrom/changeReason）
     * @param effectiveDate 生效日期（必须 >= hireDate）
     * @throws IllegalStateException 员工已离职或日期不合法
     */
    public void changeLevel(EmployeeLevel newLevel, LocalDate effectiveDate) {
        if (status != EmployeeStatusEnum.ACTIVE) {
            throw new IllegalStateException("离职员工不可变更职级");
        }
        if (effectiveDate.isBefore(hireDate)) {
            throw new IllegalStateException("职级生效日期不能早于入职日期");
        }
        EmployeeLevel current = getCurrentLevel();
        // 新生效日必须严格晚于当前职级生效日：
        // 1) 等于当天 → 违反 uk_level_employee_effective 唯一约束；
        // 2) 早于当前生效日（倒签）→ 旧记录 effective_to < effective_from 违反 CHECK 约束。
        if (current != null && !effectiveDate.isAfter(current.getEffectiveFrom())) {
            throw new IllegalStateException("职级生效日期(" + effectiveDate
                + ")必须晚于当前职级的生效日期(" + current.getEffectiveFrom() + ")");
        }
        // 关闭当前有效记录：旧记录失效日 = 新记录生效日
        if (current != null) {
            current.setEffectiveTo(effectiveDate);
        }
        levelHistory.add(newLevel);
    }

    /**
     * 获取指定时点的有效职级。
     *
     * @param pointInTime 历史时点（如算薪月份的最后一天）
     * @return 该时点有效的职级
     * @throws IllegalStateException 找不到有效职级
     */
    public EmployeeLevel getLevelAt(LocalDate pointInTime) {
        return levelHistory.stream()
            .filter(l -> !l.getEffectiveFrom().isAfter(pointInTime))
            .filter(l -> l.getEffectiveTo() == null || !l.getEffectiveTo().isBefore(pointInTime))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException(
                "员工 " + employeeCode + " 在 " + pointInTime + " 无有效职级"));
    }

    /**
     * 离职处理 —— 设置状态 + 离职日 + 关闭当前职级记录。
     *
     * @param resignDate 离职日期
     * @throws IllegalStateException 已离职或日期不合法
     */
    public void resign(LocalDate resignDate) {
        if (status == EmployeeStatusEnum.RESIGNED) {
            throw new IllegalStateException("员工已离职，不可重复操作");
        }
        if (resignDate.isBefore(hireDate)) {
            throw new IllegalStateException("离职日期不能早于入职日期");
        }
        this.status = EmployeeStatusEnum.RESIGNED;
        this.resignDate = resignDate;
        // 关闭当前职级
        EmployeeLevel current = getCurrentLevel();
        if (current != null && current.getEffectiveTo() == null) {
            current.setEffectiveTo(resignDate);
        }
    }

    /**
     * 是否兼职。
     *
     * @return true 表示兼职
     */
    public boolean isPartTime() {
        return partTimeStatus == PartTimeStatusEnum.PART_TIME;
    }

    /**
     * 是否参与社保扣款。
     * <p>
     * 规则：全职 + socialInsuranceEnabled=true → 参与。
     *
     * @return true 表示应扣社保
     */
    public boolean shouldDeductSocial() {
        return !isPartTime() && socialInsuranceEnabled;
    }

    /**
     * 获取当前有效职级（effectiveTo 为 null 的记录）。
     *
     * @return 当前职级，无则 null
     */
    public EmployeeLevel getCurrentLevel() {
        if (levelHistory == null) {
            return null;
        }
        return levelHistory.stream()
            .filter(l -> l.getEffectiveTo() == null)
            .findFirst()
            .orElse(null);
    }
}
