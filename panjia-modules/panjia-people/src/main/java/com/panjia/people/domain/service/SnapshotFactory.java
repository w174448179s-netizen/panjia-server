package com.panjia.people.domain.service;

import com.panjia.contracts.snapshot.EmployeeSnapshot;
import com.panjia.people.domain.Employee;
import com.panjia.people.domain.EmployeeLevel;
import com.panjia.people.domain.SocialInsuranceProfile;

import java.time.LocalDate;
import java.util.Date;

/**
 * Snapshot 工厂 —— 将 Employee 聚合根在指定时点的状态冻结为 {@link EmployeeSnapshot}。
 * <p>
 * 🚨 这是 Employee 离开 people 域的唯一合法形式。
 * 外部域（payroll/commission）只能接收 EmployeeSnapshot，不能直接持有 Employee。
 */
public final class SnapshotFactory {

    private SnapshotFactory() {
        // 工厂类，禁止实例化
    }

    /**
     * 按指定时点构造员工快照。
     *
     * @param employee     员工聚合根（含完整职级历史）
     * @param pointInTime  快照时点（如算薪月份最后一天）
     * @return EmployeeSnapshot（只读 POJO，可序列化）
     */
    public static EmployeeSnapshot create(Employee employee, LocalDate pointInTime) {
        EmployeeSnapshot snapshot = new EmployeeSnapshot();
        // 基本信息
        snapshot.setEmployeeId(employee.getId());
        snapshot.setEmployeeCode(employee.getEmployeeCode());
        snapshot.setName(employee.getName());
        snapshot.setDeptId(employee.getDeptId());
        snapshot.setRole(employee.getRole() == null ? null : employee.getRole().name());
        snapshot.setPartTime(employee.isPartTime());

        // 职级快照（按时点取唯一生效记录；无有效职级由 getLevelAt 抛异常，不降级）
        EmployeeLevel levelAt = employee.getLevelAt(pointInTime);
        snapshot.setLevelCode(levelAt.getLevelCode());
        snapshot.setBaseSalary(levelAt.getBaseSalary());
        snapshot.setCommissionRate(levelAt.getCommissionRate());
        snapshot.setSocialInsuranceRatio(levelAt.getSocialInsuranceRatio());

        // 社保快照
        SocialInsuranceProfile social = employee.getSocialInsurance();
        if (social != null) {
            snapshot.setSocialBaseAmount(social.getSocialBaseAmount());
            snapshot.setPersonalSocialRatio(social.getPersonalRatio());
            snapshot.setCompanySocialRatio(social.getCompanyRatio());
            snapshot.setHousingFundAmount(social.getHousingFundAmount());
            // 其他固定扣款金额（来自社保档案表）
            snapshot.setCommercialInsuranceAmount(social.getCommercialInsuranceAmount());
            snapshot.setDormitoryFee(social.getDormitoryFee());
        }

        // 开关：直接取自主表存储值（事实字段，不做业务规则判断）
        // 「兼职是否豁免」属算薪规则，下沉算薪域 pj_payroll_rule_config 配置驱动（V1.3 产品化修正）
        snapshot.setCommercialInsuranceEnabled(employee.isCommercialInsurance());
        snapshot.setDormitoryEnabled(employee.isDormitoryEnabled());

        // qualifiedApprenticeCount 由 EmployeeSnapshotService 在调本方法后补充（需注入 mapper）
        snapshot.setQualifiedApprenticeCount(0);

        snapshot.setSnapshotDate(pointInTime);
        snapshot.setSnapshotAt(new Date());
        return snapshot;
    }
}
