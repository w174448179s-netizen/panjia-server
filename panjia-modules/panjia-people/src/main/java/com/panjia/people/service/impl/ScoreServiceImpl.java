package com.panjia.people.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.panjia.contracts.dto.ScoreSummarySyncDTO;
import com.panjia.people.domain.Employee;
import com.panjia.people.domain.PerformanceScore;
import com.panjia.people.dto.ScoreQuery;
import com.panjia.people.dto.ScoreVO;
import com.panjia.people.mapper.EmployeeMapper;
import com.panjia.people.mapper.PerformanceScoreMapper;
import com.panjia.people.port.DeptPort;
import com.panjia.people.service.ScoreApprovalService;
import com.panjia.people.service.ScoreService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.domain.PageResult;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 绩效积分月度汇总服务实现（一员工一月一行）。
 * <p>
 * 数据来源为《二手积分日报5.0版》导入同步：总积分 = SUM(今日总积分)，
 * 出勤天数 = 有日报的 DISTINCT 填报日期数，平均积分 = 总积分 / 出勤天数。
 * 绩效等级与扣点口径（需求书 V4.6，与 V160001 policy.points 一致）：
 * 平均分 ≥ 8 → A（不扣）；6 ≤ 平均分 < 8 → B（-2%）；平均分 < 6 → C（-4%）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ScoreServiceImpl implements ScoreService {

    /** 数据来源：积分日报导入同步 */
    private static final String DATA_SOURCE_IMPORT = "IMPORT";

    /** 等级 A：平均分 ≥ 8 */
    private static final BigDecimal GRADE_A_MIN = new BigDecimal("8");

    /** 等级 B 下限：平均分 ≥ 6 */
    private static final BigDecimal GRADE_B_MIN = new BigDecimal("6");

    private static final BigDecimal GRADE_A_DEDUCT = BigDecimal.ZERO;
    private static final BigDecimal GRADE_B_DEDUCT = new BigDecimal("-0.02");
    private static final BigDecimal GRADE_C_DEDUCT = new BigDecimal("-0.04");

    private final PerformanceScoreMapper scoreMapper;
    private final EmployeeMapper employeeMapper;
    private final DeptPort deptPort;
    private final ScoreApprovalService approvalService;

    // ==================== 导入同步（ScoreArchiveHandler） ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void syncScoreSummaries(String period, List<ScoreSummarySyncDTO> summaries) {
        if (summaries == null || summaries.isEmpty()) {
            return;
        }
        List<String> codes = summaries.stream()
            .map(ScoreSummarySyncDTO::getEmployeeCode)
            .filter(StringUtils::isNotBlank)
            .map(String::trim)
            .distinct()
            .toList();
        if (codes.isEmpty()) {
            log.warn("[积分同步] 期间 {} 无有效工号，跳过同步", period);
            return;
        }
        Map<String, Long> codeToEmployeeId = employeeMapper.selectList(new LambdaQueryWrapper<Employee>()
                .in(Employee::getEmployeeCode, codes)
                .select(Employee::getEmployeeId, Employee::getEmployeeCode))
            .stream()
            .collect(Collectors.toMap(Employee::getEmployeeCode, Employee::getEmployeeId, (a, b) -> a));

        int synced = 0;
        int skipped = 0;
        for (ScoreSummarySyncDTO summary : summaries) {
            String code = summary.getEmployeeCode() == null ? null : summary.getEmployeeCode().trim();
            Long employeeId = code == null ? null : codeToEmployeeId.get(code);
            if (employeeId == null || summary.getScoreMonth() == null) {
                skipped++;
                log.warn("[积分同步] 工号 {} 未匹配员工档案或缺积分月份，跳过（期间 {}）", code, period);
                continue;
            }
            upsertFromImport(employeeId, summary);
            synced++;
        }
        log.info("[积分同步] 期间 {} 同步完成：成功 {} 条，跳过 {} 条", period, synced, skipped);
        if (synced > 0) {
            // 数据被导入覆盖，已提交/已通过的审批单失效回待提交，防止按旧审批算薪
            approvalService.invalidateOnDataChange(period);
        }
    }

    /** 导入行 upsert：同员工同月存在则覆盖更新，否则新增，data_source=IMPORT */
    private void upsertFromImport(Long employeeId, ScoreSummarySyncDTO summary) {
        PerformanceScore existing = scoreMapper.selectOne(new LambdaQueryWrapper<PerformanceScore>()
            .eq(PerformanceScore::getEmployeeId, employeeId)
            .eq(PerformanceScore::getScoreMonth, summary.getScoreMonth()));
        PerformanceScore target = existing;
        if (target == null) {
            target = new PerformanceScore();
            target.setEmployeeId(employeeId);
            target.setScoreMonth(summary.getScoreMonth());
        }
        applyImportMetrics(target, summary);
        target.setDataSource(DATA_SOURCE_IMPORT);
        if (existing == null) {
            try {
                scoreMapper.insert(target);
            } catch (DuplicateKeyException e) {
                // 并发导入撞唯一索引：按最新导入数据覆盖
                PerformanceScore winner = scoreMapper.selectOne(new LambdaQueryWrapper<PerformanceScore>()
                    .eq(PerformanceScore::getEmployeeId, employeeId)
                    .eq(PerformanceScore::getScoreMonth, summary.getScoreMonth()));
                if (winner != null) {
                    applyImportMetrics(winner, summary);
                    winner.setDataSource(DATA_SOURCE_IMPORT);
                    scoreMapper.updateById(winner);
                }
            }
            return;
        }
        if (scoreMapper.updateById(target) == 0) {
            // 乐观锁冲突：导入不覆盖，留待下次导入处理
            log.warn("[积分同步] 员工 {} {} 月积分正被并发修改，本次未覆盖", employeeId, summary.getScoreMonth());
        }
    }

    /** 导入指标回填 + 平均积分/等级/扣点派生（出勤 0 天时等级为 null，算薪默认 A 不扣点） */
    private void applyImportMetrics(PerformanceScore record, ScoreSummarySyncDTO summary) {
        BigDecimal totalPoints = summary.getTotalPoints() == null ? BigDecimal.ZERO : summary.getTotalPoints();
        int attendDays = summary.getAttendDays() == null ? 0 : summary.getAttendDays();
        record.setTotalPoints(totalPoints);
        record.setAttendDays(attendDays);
        if (attendDays > 0) {
            BigDecimal avg = totalPoints.divide(BigDecimal.valueOf(attendDays), 4, RoundingMode.HALF_UP);
            record.setAvgPoints(avg);
            record.setGrade(resolveGrade(avg));
            record.setDeductRate(resolveDeduct(record.getGrade()));
        } else {
            record.setAvgPoints(null);
            record.setGrade(null);
            record.setDeductRate(null);
        }
    }

    /** 平均分 → 等级：≥8 → A；≥6 → B；否则 C */
    private String resolveGrade(BigDecimal avgPoints) {
        if (avgPoints.compareTo(GRADE_A_MIN) >= 0) {
            return "A";
        }
        return avgPoints.compareTo(GRADE_B_MIN) >= 0 ? "B" : "C";
    }

    private BigDecimal resolveDeduct(String grade) {
        return switch (grade == null ? "" : grade) {
            case "A" -> GRADE_A_DEDUCT;
            case "B" -> GRADE_B_DEDUCT;
            case "C" -> GRADE_C_DEDUCT;
            default -> null;
        };
    }

    // ==================== 查询 ====================

    @Override
    public PageResult<ScoreVO> page(ScoreQuery query, PageQuery pageQuery) {
        LambdaQueryWrapper<PerformanceScore> wrapper = new LambdaQueryWrapper<>();
        applyMonthRange(wrapper, query);

        wrapper.eq(query.getEmployeeId() != null, PerformanceScore::getEmployeeId, query.getEmployeeId());

        if (StringUtils.isNotBlank(query.getEmployeeName()) || StringUtils.isNotBlank(query.getEmployeeCode())) {
            List<Long> matchedIds = findEmployeeIds(query.getEmployeeName(), query.getEmployeeCode());
            if (matchedIds.isEmpty()) {
                return PageResult.build(List.of(), 0L);
            }
            wrapper.in(PerformanceScore::getEmployeeId, matchedIds);
        }

        if (query.getDeptId() != null) {
            List<Long> deptIds = deptPort.findDeptAndChildIds(query.getDeptId());
            List<Long> employeeIds = employeeMapper.selectList(
                    new LambdaQueryWrapper<Employee>().in(Employee::getDeptId, deptIds))
                .stream().map(Employee::getEmployeeId).toList();
            if (employeeIds.isEmpty()) {
                return PageResult.build(List.of(), 0L);
            }
            wrapper.in(PerformanceScore::getEmployeeId, employeeIds);
        }

        wrapper.orderByDesc(PerformanceScore::getScoreMonth)
            .orderByDesc(PerformanceScore::getId);

        Page<PerformanceScore> page = scoreMapper.selectPage(pageQuery.build(), wrapper);
        List<ScoreVO> vos = page.getRecords().stream().map(this::toVO).toList();
        enrich(vos);
        return PageResult.build(vos, page.getTotal());
    }

    @Override
    public ScoreVO getById(Long id) {
        PerformanceScore record = scoreMapper.selectById(id);
        if (record == null) {
            throw new ServiceException("积分记录不存在，id={}", id);
        }
        ScoreVO vo = toVO(record);
        enrich(List.of(vo));
        return vo;
    }

    // ==================== 算薪绩效等级（PeopleScoreQueryPort） ====================

    @Override
    public Map<Long, String> scoreGrades(String period) {
        LocalDate monthStart = parseMonthStart(period);
        if (monthStart == null) {
            return Map.of();
        }
        List<PerformanceScore> rows = scoreMapper.selectList(new LambdaQueryWrapper<PerformanceScore>()
            .eq(PerformanceScore::getScoreMonth, monthStart)
            .isNotNull(PerformanceScore::getGrade));
        Map<Long, String> result = new HashMap<>();
        for (PerformanceScore row : rows) {
            if (row.getEmployeeId() != null && row.getGrade() != null) {
                result.put(row.getEmployeeId(), row.getGrade());
            }
        }
        return result;
    }

    // ==================== 内部方法 ====================

    private void applyMonthRange(LambdaQueryWrapper<PerformanceScore> wrapper, ScoreQuery query) {
        LocalDate start = parseMonth(query.getMonthStart());
        LocalDate end = parseMonth(query.getMonthEnd());
        wrapper.ge(start != null, PerformanceScore::getScoreMonth, start)
            .le(end != null, PerformanceScore::getScoreMonth, end);
    }

    private List<Long> findEmployeeIds(String employeeName, String employeeCode) {
        return employeeMapper.selectList(new LambdaQueryWrapper<Employee>()
                .like(StringUtils.isNotBlank(employeeName), Employee::getEmployeeName, employeeName)
                .like(StringUtils.isNotBlank(employeeCode), Employee::getEmployeeCode, employeeCode)
                .select(Employee::getEmployeeId))
            .stream().map(Employee::getEmployeeId).toList();
    }

    /** 解析月份参数（yyyy-MM-dd，前端传当月 1 日） */
    private LocalDate parseMonth(String text) {
        if (StringUtils.isBlank(text)) {
            return null;
        }
        try {
            return LocalDate.parse(text.trim());
        } catch (DateTimeParseException e) {
            throw new ServiceException("月份格式不正确，应为 yyyy-MM-dd：{}", text);
        }
    }

    private LocalDate parseMonthStart(String period) {
        if (StringUtils.isBlank(period)) {
            return null;
        }
        try {
            return java.time.YearMonth.parse(period.trim()).atDay(1);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private ScoreVO toVO(PerformanceScore record) {
        ScoreVO vo = new ScoreVO();
        BeanUtil.copyProperties(record, vo);
        return vo;
    }

    /** 批量填充工号/姓名/部门/锁定标记 */
    private void enrich(List<ScoreVO> vos) {
        if (vos.isEmpty()) {
            return;
        }
        Set<Long> employeeIds = vos.stream().map(ScoreVO::getEmployeeId)
            .filter(Objects::nonNull).collect(Collectors.toSet());
        if (employeeIds.isEmpty()) {
            return;
        }
        Map<Long, Employee> employeeMap = employeeMapper.selectByIds(employeeIds).stream()
            .collect(Collectors.toMap(Employee::getEmployeeId, e -> e, (a, b) -> a));

        Set<Long> deptIds = employeeMap.values().stream().map(Employee::getDeptId)
            .filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, String> deptNames = deptIds.isEmpty() ? Map.of() : deptPort.findDeptFullNames(deptIds);

        // 行级锁定标记：命中锁定期间（SUBMITTED/APPROVED）的行前端隐藏提交审批入口
        Set<String> months = vos.stream().map(ScoreVO::getScoreMonth)
            .filter(Objects::nonNull).map(m -> m.toString().substring(0, 7)).collect(Collectors.toSet());
        Set<String> lockedPeriods = approvalService.lockedPeriods(months);

        for (ScoreVO vo : vos) {
            Employee employee = employeeMap.get(vo.getEmployeeId());
            if (employee != null) {
                vo.setEmployeeCode(employee.getEmployeeCode());
                vo.setEmployeeName(employee.getEmployeeName());
                vo.setDeptId(employee.getDeptId());
                if (employee.getDeptId() != null) {
                    vo.setDeptName(deptNames.get(employee.getDeptId()));
                }
            }
            String period = vo.getScoreMonth() == null ? null : vo.getScoreMonth().toString().substring(0, 7);
            vo.setLocked(lockedPeriods.contains(period));
        }
    }
}
