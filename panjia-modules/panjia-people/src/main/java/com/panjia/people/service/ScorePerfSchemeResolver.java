package com.panjia.people.service;

import com.panjia.contracts.dto.PerfSchemeDTO;
import com.panjia.contracts.port.PerfSchemeQueryPort;
import com.panjia.people.domain.FactType;
import com.panjia.people.domain.SalaryFact;
import com.panjia.people.mapper.SalaryFactMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 员工绩效方案解析：PERF_SCHEME 事实 → 方案类型。
 * <p>
 * 积分扣款/扣点是否生效取决于员工被指派的绩效方案类型（NONE 无绩效不考核），
 * 方案本身由薪酬域配置，员工域经 {@link PerfSchemeQueryPort} 读取类型。
 * 未指派（事实为空）按默认方案处理，与算薪口径一致。
 */
@Component
@RequiredArgsConstructor
public class ScorePerfSchemeResolver {

    /** 积分绩效方案类型 */
    public static final String SCHEME_GRADE_RATE = "GRADE_RATE";

    /** 店长积分绩效方案类型 */
    public static final String SCHEME_GRADE_AMOUNT = "GRADE_AMOUNT";

    private final SalaryFactMapper salaryFactMapper;
    private final PerfSchemeQueryPort perfSchemeQueryPort;

    /**
     * 批量解析员工在指定时点的绩效方案类型。
     *
     * @param employeeIds 员工 ID 集合
     * @param point       取数时点（积分月份取月末）
     * @return employeeId → schemeType；方案配置缺失时值为 null
     */
    public Map<Long, String> schemeTypesAt(Collection<Long> employeeIds, LocalDate point) {
        if (employeeIds == null || employeeIds.isEmpty() || point == null) {
            return Map.of();
        }
        List<PerfSchemeDTO> schemes = perfSchemeQueryPort.listSchemes();
        Map<String, String> typeByCode = schemes.stream()
            .filter(s -> s.getCode() != null)
            .collect(Collectors.toMap(PerfSchemeDTO::getCode,
                s -> s.getSchemeType() == null ? "" : s.getSchemeType(), (a, b) -> a));
        String defaultType = schemes.stream()
            .filter(s -> Boolean.TRUE.equals(s.getIsDefault()))
            .map(PerfSchemeDTO::getSchemeType)
            .filter(java.util.Objects::nonNull)
            .findFirst()
            .orElse(null);

        Map<Long, String> result = new HashMap<>();
        for (SalaryFact fact : salaryFactMapper.selectValuesAt(
            new ArrayList<>(employeeIds), FactType.PERF_SCHEME, point)) {
            String code = fact.getValue();
            result.put(fact.getEmployeeId(), code == null || code.isBlank()
                ? defaultType
                : typeByCode.get(code.trim()));
        }
        employeeIds.forEach(id -> result.putIfAbsent(id, defaultType));
        return result;
    }

    /**
     * 晚提交扣款是否豁免：仅积分绩效（GRADE_RATE）、店长积分绩效（GRADE_AMOUNT）考核，
     * 不分绩效等级；无绩效、业绩阶梯等其他方案不考核。
     */
    public boolean isLateFeeExempt(String schemeType) {
        return !SCHEME_GRADE_RATE.equals(schemeType) && !SCHEME_GRADE_AMOUNT.equals(schemeType);
    }
}
