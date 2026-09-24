package com.panjia.people.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.panjia.contracts.dto.SalaryFactSyncDTO;
import com.panjia.contracts.port.PeopleSalaryFactSyncPort;
import com.panjia.people.domain.FactType;
import com.panjia.people.domain.SalaryFact;
import com.panjia.people.mapper.SalaryFactMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.utils.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 算薪事实历史同步适配器（{@link PeopleSalaryFactSyncPort} people 域实现）。
 * <p>
 * 历史工资导入批次归档后，payroll 域 PayrollArchiveHandler 从工资族归一化行
 * 推导 LEVEL / SOCIAL / SOCIAL_FEE / HOUSING / HOUSING_FUND / COMMERCIAL /
 * COMMERCIAL_FEE / DORMITORY / DORMITORY_FEE 事实，经本端口写入
 * pj_people_salary_fact（表归 people 域管辖，payroll 域不直写）。
 * <p>
 * 语义（对齐老导入器 processSalaryFactSegment）：
 * <ul>
 *   <li>切片区间 [月初, 次月初)，不改既有事实链、不影响其他月份取数；</li>
 *   <li>与月初时点的既有事实链比对，值一致跳过（不产生冗余切片）；</li>
 *   <li>同员工同类型当月已有 HIST_IMPORT 切片跳过（重导幂等）；</li>
 *   <li>change_field='HIST_IMPORT'，批次撤销时按此标记清理。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SalaryFactSyncAdapter implements PeopleSalaryFactSyncPort {

    /** 历史导入写入的算薪事实标识（pj_people_salary_fact.change_field），撤销清理依据 */
    public static final String FACT_CHANGE_FIELD = "HIST_IMPORT";

    private final SalaryFactMapper salaryFactMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int syncHistorySalaryFacts(String period, List<SalaryFactSyncDTO> facts) {
        if (StringUtils.isBlank(period) || facts == null || facts.isEmpty()) {
            return 0;
        }
        LocalDate monthStart;
        try {
            monthStart = YearMonth.parse(period.trim()).atDay(1);
        } catch (DateTimeParseException e) {
            log.warn("[算薪事实] 期间 {} 非法，跳过同步 {} 条", period, facts.size());
            return 0;
        }
        LocalDate monthEnd = monthStart.plusMonths(1);

        // 批量预取：本批涉及员工的既有当月切片 + 月初时点事实链（避免循环内逐条查询）
        Set<Long> employeeIds = new HashSet<>();
        for (SalaryFactSyncDTO fact : facts) {
            if (fact != null && fact.employeeId() != null) {
                employeeIds.add(fact.employeeId());
            }
        }
        // 当月已有 HIST_IMPORT 切片的 (employeeId, factType) 集合（重导幂等）
        Set<String> importedKeys = new HashSet<>();
        // 月初时点既有值锚点 (employeeId, factType) → value
        java.util.Map<String, String> currentValues = new java.util.HashMap<>();
        if (!employeeIds.isEmpty()) {
            List<SalaryFact> existing = salaryFactMapper.selectList(
                new LambdaQueryWrapper<SalaryFact>()
                    .in(SalaryFact::getEmployeeId, employeeIds)
                    .and(w -> w
                        // 当月 HIST_IMPORT 切片
                        .ge(SalaryFact::getEffectiveDate, monthStart)
                        .lt(SalaryFact::getEffectiveDate, monthEnd)
                        .eq(SalaryFact::getChangeField, FACT_CHANGE_FIELD)
                        // 或月初时点生效的历史事实链
                        .or(or -> or
                            .le(SalaryFact::getEffectiveDate, monthStart)
                            .and(w2 -> w2.isNull(SalaryFact::getExpireDate)
                                .or().gt(SalaryFact::getExpireDate, monthStart)))));
            for (SalaryFact f : existing) {
                boolean histSlice = FACT_CHANGE_FIELD.equals(f.getChangeField())
                    && !f.getEffectiveDate().isBefore(monthStart)
                    && f.getEffectiveDate().isBefore(monthEnd);
                if (histSlice) {
                    importedKeys.add(f.getEmployeeId() + "|" + f.getFactType().name());
                } else if (f.getEffectiveDate().equals(monthStart)
                    || f.getEffectiveDate().isBefore(monthStart)) {
                    // 事实链取数按 effective_date 倒序取第一条：此处用 putIfAbsent 保留最新
                    // （selectList 无排序，同类型多条时以任意一条为准即可满足"一致跳过"粗判）
                    currentValues.putIfAbsent(f.getEmployeeId() + "|" + f.getFactType().name(), f.getValue());
                }
            }
        }

        int inserted = 0;
        int same = 0;
        int skipped = 0;
        for (SalaryFactSyncDTO fact : facts) {
            if (fact == null || fact.employeeId() == null || StringUtils.isBlank(fact.factType())) {
                skipped++;
                continue;
            }
            FactType type;
            try {
                type = FactType.valueOf(fact.factType());
            } catch (IllegalArgumentException e) {
                log.warn("[算薪事实] 未知事实类型 {}，跳过：employeeId={}", fact.factType(), fact.employeeId());
                skipped++;
                continue;
            }
            String key = fact.employeeId() + "|" + type.name();
            if (importedKeys.contains(key)) {
                skipped++;
                continue;
            }
            String value = fact.value() == null ? "" : fact.value().trim();
            String current = currentValues.get(key);
            if (value.equals(current == null ? null : current.trim())) {
                same++;
                continue;
            }
            try {
                SalaryFact entity = new SalaryFact();
                entity.setEmployeeId(fact.employeeId());
                entity.setFactType(type);
                entity.setValue(value);
                entity.setEffectiveDate(monthStart);
                entity.setExpireDate(monthEnd);
                entity.setChangeField(FACT_CHANGE_FIELD);
                salaryFactMapper.insert(entity);
                importedKeys.add(key);
                inserted++;
            } catch (Exception e) {
                log.warn("[算薪事实] 插入失败跳过：employeeId={}, type={}，{}",
                    fact.employeeId(), type, e.getMessage());
                skipped++;
            }
        }
        log.info("[算薪事实] 历史同步完成：period={}, 插入 {} 条（与既有一致跳过 {}，幂等/非法跳过 {}）",
            period, inserted, same, skipped);
        return inserted;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int deleteHistoryFacts(String period) {
        if (StringUtils.isBlank(period)) {
            return 0;
        }
        LocalDate monthStart;
        try {
            monthStart = YearMonth.parse(period.trim()).atDay(1);
        } catch (DateTimeParseException e) {
            log.warn("[算薪事实] 撤销期间 {} 非法，跳过清理", period);
            return 0;
        }
        int deleted = salaryFactMapper.delete(new LambdaQueryWrapper<SalaryFact>()
            .eq(SalaryFact::getChangeField, FACT_CHANGE_FIELD)
            .ge(SalaryFact::getEffectiveDate, monthStart)
            .lt(SalaryFact::getEffectiveDate, monthStart.plusMonths(1)));
        log.info("[算薪事实] 历史导入切片清理：period={}, 删除 {} 条", period, deleted);
        return deleted;
    }
}
