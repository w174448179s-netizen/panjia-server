package com.panjia.people.domain.service;

import com.panjia.people.domain.EmployeeLevel;
import com.panjia.people.domain.SocialInsuranceProfile;
import org.dromara.common.core.exception.ServiceException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

/**
 * 员工领域服务 —— 职级模板取值与档案初始化规则。
 * <p>
 * 职级-薪酬映射 V1 固化（§2.2.2）；算薪时按快照取值，不实时查此表。
 */
@Service
public class EmployeeDomainService {

    /** 社保基数默认值 */
    private static final BigDecimal DEFAULT_SOCIAL_BASE = new BigDecimal("1637.15");

    /**
     * 职级模板（V1 固化）：levelCode → [levelName, baseSalary, commissionRate, socialInsuranceRatio]。
     */
    private static final Map<String, String[]> LEVEL_TEMPLATE = Map.ofEntries(
        Map.entry("A0", new String[]{"新人", "4500.00", "0.2000", "0.2000"}),
        Map.entry("A1", new String[]{"A1", "0.00", "0.5500", "0.5500"}),
        Map.entry("A2", new String[]{"A2", "0.00", "0.6000", "0.6000"}),
        Map.entry("A3", new String[]{"A3", "0.00", "0.6500", "0.6500"}),
        Map.entry("A4", new String[]{"A4", "0.00", "0.6700", "0.6700"}),
        Map.entry("A5", new String[]{"A5", "0.00", "0.7000", "0.7000"}),
        Map.entry("S1", new String[]{"初级店长", "7000.00", "0.7000", "0.3000"}),
        Map.entry("S2", new String[]{"高级店长", "8000.00", "0.7000", "0.3000"}),
        Map.entry("DIRECTOR", new String[]{"总监", "6000.00", "0.7000", "0.3000"})
    );

    /**
     * 按职级编码构造初始职级记录（填充模板薪酬参数）。
     *
     * @param employeeId    员工 ID
     * @param levelCode     职级编码
     * @param effectiveFrom 生效日期（入职日）
     * @param reason        变更原因（如"初始化"）
     * @return 未持久化的职级记录
     */
    public EmployeeLevel createInitialLevel(Long employeeId, String levelCode,
                                            LocalDate effectiveFrom, String reason) {
        String[] template = LEVEL_TEMPLATE.get(levelCode);
        if (template == null) {
            throw new ServiceException("未知职级编码: " + levelCode);
        }
        EmployeeLevel level = new EmployeeLevel();
        level.setEmployeeId(employeeId);
        level.setLevelCode(levelCode);
        level.setLevelName(template[0]);
        level.setBaseSalary(new BigDecimal(template[1]));
        level.setCommissionRate(new BigDecimal(template[2]));
        level.setSocialInsuranceRatio(new BigDecimal(template[3]));
        level.setEffectiveFrom(effectiveFrom);
        level.setChangeReason(reason);
        return level;
    }

    /**
     * 构造初始社保档案。
     *
     * @param employeeId     员工 ID
     * @param personalRatio  个人承担比例（公司比例 = 1 - personalRatio）
     * @param effectiveFrom  生效日期（建档时应传员工入职日，与初始职级一致，勿传当天日期）
     * @return 未持久化的社保档案
     */
    public SocialInsuranceProfile createSocialInsurance(Long employeeId, BigDecimal personalRatio,
                                                        LocalDate effectiveFrom) {
        SocialInsuranceProfile profile = new SocialInsuranceProfile();
        profile.setEmployeeId(employeeId);
        profile.setSocialBaseAmount(DEFAULT_SOCIAL_BASE);
        profile.setPersonalRatio(personalRatio);
        // 公司承担比例 = 1 - 个人比例
        profile.setCompanyRatio(BigDecimal.ONE.subtract(personalRatio));
        profile.setHousingFundAmount(BigDecimal.ZERO);
        profile.setCommercialInsuranceAmount(new BigDecimal("21"));
        profile.setDormitoryFee(BigDecimal.ZERO);
        profile.setEffectiveFrom(effectiveFrom);
        return profile;
    }
}
