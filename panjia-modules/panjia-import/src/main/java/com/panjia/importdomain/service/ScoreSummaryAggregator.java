package com.panjia.importdomain.service;

import com.panjia.contracts.dto.ScoreSummarySyncDTO;
import com.panjia.importdomain.domain.NormalizedRecord;
import com.panjia.importdomain.domain.NormalizedRecordType;
import com.panjia.importdomain.domain.raw.RawPoints;
import com.panjia.importdomain.mapper.NormalizedRecordMapper;
import com.panjia.importdomain.mapper.RawPointsMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
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
 * <p>
 * 提交时间规则（业务需求 V4.6+）：
 * <ul>
 *   <li>有效提交窗口：每日 19:30 ~ 23:00</li>
 *   <li>早于 19:30 提交：视为无效，当日积分不计入总积分（但仍计出勤）</li>
 *   <li>晚于 23:00 提交：积分有效，但记 1 次晚提交（算薪时扣款 5 元/次）</li>
 *   <li>特殊情况（谈单到深夜）经总监同意可免处罚——免罚由人工在积分审批单中调整，
 *       聚合器不做判断，只统计原始晚提交次数</li>
 * </ul>
 * 聚合结果作为事件 payload（scoreSummaries）随 Outbox 投递，员工域
 * ScoreArchiveHandler 消费后经 Port 写积分表（推模式，与考勤域同构）。
 * 同步失败可由 OutboxDispatcher 重试，upsert（同人同月覆盖）保证重投幂等。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ScoreSummaryAggregator {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** 晚提交扣款单价：积分扣款金额 ÷ 5 = 晚提交次数（老导入器口径） */
    private static final BigDecimal LATE_FEE_UNIT = new BigDecimal("5");

    private final RawPointsMapper rawPointsMapper;
    private final NormalizedRecordMapper normalizedRecordMapper;

    /**
     * 聚合批次的积分月度汇总（非积分类型/无数据返回空列表）。
     *
     * @param batchId    已归档批次 ID
     * @param sourceType 批次来源类型代码（POINTS/ATTENDANCE/HISTORY_PAYROLL/...）
     * @param period     归属期间（YYYY-MM）
     */
    public List<ScoreSummarySyncDTO> aggregateIfPoints(Long batchId, String sourceType, String period) {
        // 历史工资：月度总量行（日报窗口/去重逻辑不适用），从归一化记录聚合
        if ("HISTORY_PAYROLL".equals(sourceType)) {
            return aggregateHistory(batchId, period);
        }
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

        // 有效提交窗口：19:30 ~ 23:00
        // 早于 19:30 → 无效（积分不计）；晚于 23:00 → 有效但计晚提交 1 次
        final LocalTime WINDOW_START = LocalTime.of(19, 30);
        final LocalTime WINDOW_END = LocalTime.of(23, 0);

        // 同工号聚合（日报一人一天一行；防御性处理同文件重复行：
        // 总积分累加，出勤天数按 DISTINCT 填报日期计数防同日重复行多算）
        Map<String, ScoreSummarySyncDTO> byCode = new LinkedHashMap<>();
        Map<String, java.util.Set<LocalDate>> datesByCode = new LinkedHashMap<>();
        // 晚提交按「工号+日期」去重：同一天多次晚提交只计 1 次
        Map<String, java.util.Set<LocalDate>> lateDatesByCode = new LinkedHashMap<>();
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
                d.setLateSubmitCount(0);
                return d;
            });
            LocalDateTime submitTime = raw.getSubmitTime();
            LocalDate pointDay = raw.getPointDate() != null ? raw.getPointDate()
                : (submitTime != null ? submitTime.toLocalDate() : null);

            // 出勤天数：有填报记录即计出勤（无论时间是否在窗口内）
            if (pointDay != null) {
                datesByCode.computeIfAbsent(code, k -> new java.util.HashSet<>()).add(pointDay);
            }

            // 提交时间判定：早于 19:30 无效不计积分；晚于 23:00 计晚提交。
            // 提交时间缺失/解析失败（submitTime == null）时防御性按有效计分：
            // 薪点数据宁多算不漏算，避免模板映射或格式问题静默清零整月积分
            boolean inWindow = submitTime == null
                || (!submitTime.toLocalTime().isBefore(WINDOW_START)
                    && !submitTime.toLocalTime().isAfter(WINDOW_END));
            boolean late = submitTime != null && submitTime.toLocalTime().isAfter(WINDOW_END);

            if (inWindow || late) {
                // 窗口内或晚提交：积分有效
                if (raw.getScore() != null) {
                    dto.setTotalPoints(dto.getTotalPoints().add(raw.getScore()));
                }
            }
            // 早于 19:30：积分无效，不计入总积分

            if (late && pointDay != null) {
                boolean isNewLate = lateDatesByCode
                    .computeIfAbsent(code, k -> new java.util.HashSet<>()).add(pointDay);
                if (isNewLate) {
                    dto.setLateSubmitCount(dto.getLateSubmitCount() + 1);
                }
            }
        }
        for (Map.Entry<String, java.util.Set<LocalDate>> e : datesByCode.entrySet()) {
            byCode.get(e.getKey()).setAttendDays(e.getValue().size());
        }
        return new ArrayList<>(byCode.values());
    }

    /**
     * 历史工资批次积分汇总：recordType=POINTS 归一化记录（总积分/出勤天数为月度
     * 总量，工号在归一化期匹配）+ 工资族 WAGE 行「积分扣款」÷5 折算晚提交次数
     * （老导入器口径，金额为负先取绝对值，HALF_UP 取整）。
     */
    private List<ScoreSummarySyncDTO> aggregateHistory(Long batchId, String period) {
        LocalDate scoreMonth = parseMonthStart(period);
        if (scoreMonth == null) {
            log.warn("[积分聚合] 期间 {} 非法，跳过 batchId={}", period, batchId);
            return List.of();
        }
        List<NormalizedRecord> pointRecords = normalizedRecordMapper.selectList(
            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<NormalizedRecord>()
                .eq(NormalizedRecord::getBatchId, batchId)
                .eq(NormalizedRecord::getRecordType, NormalizedRecordType.POINTS));
        if (pointRecords.isEmpty()) {
            return List.of();
        }
        // 工资表积分扣款 → 晚提交次数（工号 → 累计扣款额）
        Map<String, BigDecimal> lateFeeByCode = new LinkedHashMap<>();
        List<NormalizedRecord> wageRecords = normalizedRecordMapper.selectList(
            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<NormalizedRecord>()
                .eq(NormalizedRecord::getBatchId, batchId)
                .eq(NormalizedRecord::getRecordType, NormalizedRecordType.PAYROLL_WAGE));
        for (NormalizedRecord wage : wageRecords) {
            JsonNode extra = readJson(wage.getExtraJson());
            if (!"WAGE".equals(text(extra, "sheetKind"))) {
                continue;
            }
            BigDecimal fee = dec(extra, "pointsFee");
            if (fee != null && wage.getEmployeeExternalCode() != null) {
                lateFeeByCode.merge(wage.getEmployeeExternalCode(), fee.abs(), BigDecimal::add);
            }
        }

        List<ScoreSummarySyncDTO> out = new ArrayList<>();
        for (NormalizedRecord rec : pointRecords) {
            String code = rec.getEmployeeExternalCode() == null
                ? null : rec.getEmployeeExternalCode().trim();
            if (code == null || code.isEmpty()) {
                continue;
            }
            ScoreSummarySyncDTO dto = new ScoreSummarySyncDTO();
            dto.setEmployeeCode(code);
            dto.setScoreMonth(scoreMonth);
            dto.setTotalPoints(rec.getReceivableAmount() == null ? BigDecimal.ZERO : rec.getReceivableAmount());
            JsonNode extra = readJson(rec.getExtraJson());
            Integer attendDays = intOf(extra, "attendDays");
            dto.setAttendDays(attendDays == null ? 0 : attendDays);
            BigDecimal lateFee = lateFeeByCode.get(code);
            dto.setLateSubmitCount(lateFee == null ? 0
                : lateFee.divide(LATE_FEE_UNIT, 0, RoundingMode.HALF_UP).intValue());
            out.add(dto);
        }
        return out;
    }

    private JsonNode readJson(String text) {
        if (text == null || text.isBlank()) {
            return JSON.nullNode();
        }
        try {
            return JSON.readTree(text);
        } catch (Exception e) {
            return JSON.nullNode();
        }
    }

    /** JSON 数值字段（Excel 解析器统一序列化为字符串，数值/文本节点均需兼容） */
    private BigDecimal dec(JsonNode node, String field) {
        JsonNode v = node.path(field);
        if (v.isMissingNode() || v.isNull()) {
            return null;
        }
        String text = v.isNumber() ? v.asText() : (v.isTextual() ? v.asText().trim() : null);
        if (text == null || text.isEmpty()) {
            return null;
        }
        try {
            return new BigDecimal(text);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Integer intOf(JsonNode node, String field) {
        BigDecimal value = dec(node, field);
        return value == null ? null : value.intValue();
    }

    private String text(JsonNode node, String field) {
        JsonNode v = node.path(field);
        if (v.isMissingNode() || v.isNull()) {
            return null;
        }
        return v.isTextual() ? v.asText().trim() : v.asText();
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
