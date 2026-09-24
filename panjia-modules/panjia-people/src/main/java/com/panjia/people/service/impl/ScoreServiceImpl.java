package com.panjia.people.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.panjia.contracts.dto.PointsRuleDTO;
import com.panjia.contracts.dto.ScoreFactsDTO;
import com.panjia.contracts.dto.ScoreSummarySyncDTO;
import com.panjia.people.domain.Employee;
import com.panjia.people.domain.PerformanceScore;
import com.panjia.people.dto.ScoreQuery;
import com.panjia.people.dto.ScoreVO;
import com.panjia.people.mapper.EmployeeMapper;
import com.panjia.people.mapper.PerformanceScoreMapper;
import com.panjia.people.port.DeptPort;
import com.panjia.people.service.ScoreApprovalService;
import com.panjia.people.service.ScoreGradePolicy;
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
import java.time.LocalDate;
import java.time.YearMonth;
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
 * 出勤天数 = 有日报的 DISTINCT 填报日期数。
 * 落库只存原始事实（总积分/出勤天数/晚提交次数）；平均积分、绩效等级、
 * 提成扣点、积分扣款为派生字段，查询时按 {@link ScoreGradePolicy} 实时计算。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ScoreServiceImpl implements ScoreService {

    /** 数据来源：积分日报导入同步 */
    private static final String DATA_SOURCE_IMPORT = "IMPORT";

    private final PerformanceScoreMapper scoreMapper;
    private final EmployeeMapper employeeMapper;
    private final DeptPort deptPort;
    private final ScoreApprovalService approvalService;
    private final ScoreGradePolicy scoreGradePolicy;

    // ==================== 导入同步（ScoreArchiveHandler） ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void syncScoreSummaries(String period, List<ScoreSummarySyncDTO> summaries) {
        int synced = doSyncSummaries(period, summaries);
        if (synced > 0) {
            // 数据被导入覆盖，已提交/已通过的审批单失效回待提交，防止按旧审批算薪
            approvalService.invalidateOnDataChange(period);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void syncHistorySummaries(String period, List<ScoreSummarySyncDTO> summaries) {
        int synced = doSyncSummaries(period, summaries);
        if (synced > 0) {
            // 历史补录：审批单直接置 APPROVED 终态（无流程实例），不做失效打回
            approvalService.approveForHistory(period);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void revokeHistoryImport(String period) {
        LocalDate monthStart = parseMonth(period);
        if (monthStart == null) {
            return;
        }
        int rows = scoreMapper.delete(new LambdaQueryWrapper<PerformanceScore>()
            .eq(PerformanceScore::getScoreMonth, monthStart)
            .eq(PerformanceScore::getDataSource, DATA_SOURCE_IMPORT));
        approvalService.deleteHistoryApproval(period);
        log.info("[积分撤销] 历史导入数据已清理：period={}, 删除积分 {} 条", period, rows);
    }

    /** 汇总 upsert 公共段：按工号匹配员工后逐条覆盖写入，返回成功条数。 */
    private int doSyncSummaries(String period, List<ScoreSummarySyncDTO> summaries) {
        if (summaries == null || summaries.isEmpty()) {
            return 0;
        }
        List<String> codes = summaries.stream()
            .map(ScoreSummarySyncDTO::getEmployeeCode)
            .filter(StringUtils::isNotBlank)
            .map(String::trim)
            .distinct()
            .toList();
        if (codes.isEmpty()) {
            log.warn("[积分同步] 期间 {} 无有效工号，跳过同步", period);
            return 0;
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
        return synced;
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

    /** 导入指标回填：只存原始事实，平均积分/等级/扣点/积分扣款查询时实时计算 */
    private void applyImportMetrics(PerformanceScore record, ScoreSummarySyncDTO summary) {
        record.setTotalPoints(summary.getTotalPoints() == null ? BigDecimal.ZERO : summary.getTotalPoints());
        record.setAttendDays(summary.getAttendDays() == null ? 0 : summary.getAttendDays());
        // 晚提交次数为原始事实（免罚由人事在锁定前调整该值，扣款随之实时变化）
        record.setLateSubmitCount(summary.getLateSubmitCount() == null ? 0 : summary.getLateSubmitCount());
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
        PointsRuleDTO rule = scoreGradePolicy.currentRule();
        List<ScoreVO> vos = page.getRecords().stream().map(r -> toVO(r, rule)).toList();
        enrich(vos);
        return PageResult.build(vos, page.getTotal());
    }

    @Override
    public ScoreVO getById(Long id) {
        PerformanceScore record = scoreMapper.selectById(id);
        if (record == null) {
            throw new ServiceException("积分记录不存在，id={}", id);
        }
        ScoreVO vo = toVO(record, scoreGradePolicy.currentRule());
        enrich(List.of(vo));
        return vo;
    }

    // ==================== 手工新增 / 删除 ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void create(Long employeeId, YearMonth scoreMonth, BigDecimal totalPoints,
                       Integer attendDays, Integer lateSubmitCount) {
        if (employeeId == null || scoreMonth == null) {
            throw new ServiceException("员工和积分月份不能为空");
        }
        if (totalPoints == null || totalPoints.signum() < 0) {
            throw new ServiceException("总积分不能为空且不能为负数");
        }
        if (attendDays == null || attendDays < 0) {
            throw new ServiceException("出勤天数不能为空且不能为负数");
        }
        if (lateSubmitCount == null || lateSubmitCount < 0) {
            throw new ServiceException("晚提交次数不能为空且不能为负数");
        }
        Employee employee = employeeMapper.selectById(employeeId);
        if (employee == null) {
            throw new ServiceException("员工不存在，请重新选择");
        }
        String period = scoreMonth.toString();
        if (approvalService.isPeriodLocked(period)) {
            throw new ServiceException("该期间积分已提交审批或已通过，无法新增");
        }
        Long existed = scoreMapper.selectList(new LambdaQueryWrapper<PerformanceScore>()
                .eq(PerformanceScore::getEmployeeId, employeeId)
                .eq(PerformanceScore::getScoreMonth, scoreMonth.atDay(1)))
            .stream().findFirst().map(PerformanceScore::getId).orElse(null);
        if (existed != null) {
            throw new ServiceException("该员工当月已有积分记录，请直接修改");
        }
        PerformanceScore record = new PerformanceScore();
        record.setEmployeeId(employeeId);
        record.setScoreMonth(scoreMonth.atDay(1));
        record.setTotalPoints(totalPoints);
        record.setAttendDays(attendDays);
        record.setLateSubmitCount(lateSubmitCount);
        record.setDataSource("MANUAL");
        try {
            scoreMapper.insert(record);
        } catch (DuplicateKeyException e) {
            throw new ServiceException("该员工当月已有积分记录，请直接修改");
        }
        log.info("[积分] 手工新增：员工={}, 月份={}, 总积分={}, 出勤={}天, 晚提交={}次",
            employeeId, period, totalPoints, attendDays, lateSubmitCount);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        PerformanceScore record = scoreMapper.selectById(id);
        if (record == null) {
            throw new ServiceException("积分记录不存在，id={}", id);
        }
        if (record.getScoreMonth() != null) {
            String period = record.getScoreMonth().toString().substring(0, 7);
            if (approvalService.isPeriodLocked(period)) {
                throw new ServiceException("该期间积分已提交审批或已通过，无法删除");
            }
        }
        scoreMapper.deleteById(id);
        log.info("[积分] 删除：id={}, 员工={}, 月份={}", id, record.getEmployeeId(), record.getScoreMonth());
    }

    // ==================== 修改原始事实（数据修正/晚提交豁免） ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateRawFacts(Long id, BigDecimal totalPoints, Integer attendDays, Integer lateSubmitCount) {
        if (totalPoints == null || totalPoints.signum() < 0) {
            throw new ServiceException("总积分不能为空且不能为负数");
        }
        if (attendDays == null || attendDays < 0) {
            throw new ServiceException("出勤天数不能为空且不能为负数");
        }
        if (lateSubmitCount == null || lateSubmitCount < 0) {
            throw new ServiceException("晚提交次数不能为空且不能为负数");
        }
        PerformanceScore record = scoreMapper.selectById(id);
        if (record == null) {
            throw new ServiceException("积分记录不存在，id={}", id);
        }
        // 期间锁定校验：已提交审批或已通过的期间不允许修改
        if (record.getScoreMonth() != null) {
            String period = record.getScoreMonth().toString().substring(0, 7);
            if (approvalService.isPeriodLocked(period)) {
                throw new ServiceException("该期间积分已提交审批或已通过，无法修改");
            }
        }
        record.setTotalPoints(totalPoints);
        record.setAttendDays(attendDays);
        record.setLateSubmitCount(lateSubmitCount);
        if (scoreMapper.updateById(record) == 0) {
            throw new ServiceException("修改失败，记录已被他人修改，请刷新后重试");
        }
        log.info("[积分] 原始事实修改：id={}, 员工={}, 总积分→{}, 出勤→{}天, 晚提交→{}次, 扣款={}",
            id, record.getEmployeeId(), totalPoints, attendDays, lateSubmitCount,
            scoreGradePolicy.lateFeeOf(scoreGradePolicy.currentRule(), lateSubmitCount));
    }

    // ==================== 算薪绩效事实（PeopleScoreQueryPort） ====================

    /**
     * 仅返回原始事实（总积分/出勤天数/晚提交次数），不做等级判定、不算扣款——
     * 由薪酬引擎按 policy.points 规则推导（参见 SalaryCalculationEngine.resolveGrade/LateFee）。
     * 与 {@code AttendanceMetricsDTO} 同模式：port 只装事实。
     */
    @Override
    public Map<Long, ScoreFactsDTO> scoreFacts(String period) {
        LocalDate monthStart = parseMonthStart(period);
        if (monthStart == null) {
            return Map.of();
        }
        List<PerformanceScore> rows = scoreMapper.selectList(new LambdaQueryWrapper<PerformanceScore>()
            .eq(PerformanceScore::getScoreMonth, monthStart));
        Map<Long, ScoreFactsDTO> result = new HashMap<>();
        for (PerformanceScore row : rows) {
            if (row.getEmployeeId() == null) {
                continue;
            }
            ScoreFactsDTO dto = new ScoreFactsDTO();
            dto.setTotalPoints(row.getTotalPoints());
            dto.setAttendDays(row.getAttendDays());
            dto.setLateSubmitCount(row.getLateSubmitCount());
            result.put(row.getEmployeeId(), dto);
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

    private ScoreVO toVO(PerformanceScore record, PointsRuleDTO rule) {
        ScoreVO vo = new ScoreVO();
        BeanUtil.copyProperties(record, vo);
        // 派生字段实时计算（不落库）：平均积分/绩效等级/提成扣点/积分扣款
        vo.setPointsFee(scoreGradePolicy.lateFeeOf(rule, record.getLateSubmitCount()));
        BigDecimal avg = scoreGradePolicy.avgPoints(record.getTotalPoints(), record.getAttendDays());
        vo.setAvgPoints(avg);
        vo.setGrade(avg == null ? null : scoreGradePolicy.resolveGrade(rule, avg));
        vo.setDeductRate(scoreGradePolicy.deductOf(rule, vo.getGrade()));
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
