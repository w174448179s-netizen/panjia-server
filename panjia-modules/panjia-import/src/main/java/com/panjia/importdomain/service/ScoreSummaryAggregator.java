package com.panjia.importdomain.service;

import com.panjia.contracts.dto.ScoreSummarySyncDTO;
import com.panjia.importdomain.domain.raw.RawPoints;
import com.panjia.importdomain.mapper.RawPointsMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 积分批次 → 月度汇总聚合器（纯读，无跨域副作用）。
 * <p>
 * 归档路径构造 {@code ImportBatchArchivedEvent} 时调用：《二手积分日报5.0版》
 * 为一人一天一行的日报，按工号聚合为一人一月——总积分 = SUM(今日总积分)，
 * 出勤天数 = COUNT(DISTINCT 填报日期)（报了积分即视为出勤）。
 * 聚合结果作为事件 payload（scoreSummaries）随 Outbox 投递，员工域
 * ScoreArchiveHandler 消费后经 Port 写积分表（推模式，与考勤域同构）。
 * 同步失败可由 OutboxDispatcher 重试，upsert（同人同月覆盖）保证重投幂等。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ScoreSummaryAggregator {

    private final RawPointsMapper rawPointsMapper;

    /**
     * 聚合批次的积分月度汇总（非积分类型/无数据返回空列表）。
     *
     * @param batchId    已归档批次 ID
     * @param sourceType 批次来源类型代码（POINTS/ATTENDANCE/...）
     * @param period     归属期间（YYYY-MM）
     */
    public List<ScoreSummarySyncDTO> aggregateIfPoints(Long batchId, String sourceType, String period) {
        if (!"POINTS".equals(sourceType)) {
            return List.of();
        }
        LocalDate scoreMonth = parseMonthStart(period);
        if (scoreMonth == null) {
            log.warn("[积分聚合] 期间 {} 非法，跳过 batchId={}", period, batchId);
            return List.of();
        }
        List<RawPoints> rows = rawPointsMapper.selectList(
            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<RawPoints>()
                .eq(RawPoints::getBatchId, batchId));
        if (rows.isEmpty()) {
            return List.of();
        }

        // 同工号聚合（日报一人一天一行；防御性处理同文件重复行：
        // 总积分累加，出勤天数按 DISTINCT 填报日期计数防同日重复行多算）
        Map<String, ScoreSummarySyncDTO> byCode = new LinkedHashMap<>();
        Map<String, java.util.Set<LocalDate>> datesByCode = new LinkedHashMap<>();
        for (RawPoints raw : rows) {
            String code = raw.getEmployeeCode() == null ? null : raw.getEmployeeCode().trim();
            if (code == null || code.isEmpty()) {
                continue;
            }
            ScoreSummarySyncDTO dto = byCode.computeIfAbsent(code, k -> {
                ScoreSummarySyncDTO d = new ScoreSummarySyncDTO();
                d.setEmployeeCode(k);
                d.setScoreMonth(scoreMonth);
                d.setTotalPoints(BigDecimal.ZERO);
                d.setAttendDays(0);
                return d;
            });
            if (raw.getScore() != null) {
                dto.setTotalPoints(dto.getTotalPoints().add(raw.getScore()));
            }
            if (raw.getPointDate() != null) {
                datesByCode.computeIfAbsent(code, k -> new java.util.HashSet<>()).add(raw.getPointDate());
            }
        }
        for (Map.Entry<String, java.util.Set<LocalDate>> e : datesByCode.entrySet()) {
            byCode.get(e.getKey()).setAttendDays(e.getValue().size());
        }
        return new ArrayList<>(byCode.values());
    }

    /** 归属月（YYYY-MM）→ 当月 1 日；非法返回 null */
    private LocalDate parseMonthStart(String period) {
        if (period == null || period.isBlank()) {
            return null;
        }
        try {
            return YearMonth.parse(period.trim()).atDay(1);
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
