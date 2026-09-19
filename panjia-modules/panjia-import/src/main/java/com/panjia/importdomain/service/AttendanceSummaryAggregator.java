package com.panjia.importdomain.service;

import com.panjia.contracts.dto.AttendanceSummarySyncDTO;
import com.panjia.importdomain.domain.raw.RawAttendance;
import com.panjia.importdomain.mapper.RawAttendanceMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 考勤批次 → 月度汇总聚合器（纯读，无跨域副作用）。
 * <p>
 * 归档路径构造 {@code ImportBatchArchivedEvent} 时调用：
 * 按批次读原始考勤行（一行一人一月），从实体列与 raw_json 提取月度指标，
 * 聚合结果作为事件 payload（attendanceSummaries）随 Outbox 投递。
 * 员工域 AttendanceArchiveHandler 消费后经 Port 写考勤汇总表（推模式，
 * 与业绩域消费归档事件同构）。同步失败可由 OutboxDispatcher 重试，
 * upsert（同人同月覆盖）保证重投幂等。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AttendanceSummaryAggregator {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final RawAttendanceMapper rawAttendanceMapper;

    /**
     * 聚合批次的考勤月度汇总（非考勤类型/无数据返回空列表）。
     *
     * @param batchId    已归档批次 ID
     * @param sourceType 批次来源类型代码（KE_SIGNED/ATTENDANCE/...）
     * @param period     归属期间（YYYY-MM）
     */
    public List<AttendanceSummarySyncDTO> aggregateIfAttendance(Long batchId, String sourceType, String period) {
        if (!"ATTENDANCE".equals(sourceType)) {
            return List.of();
        }
        LocalDate attendMonth = parseMonthStart(period);
        if (attendMonth == null) {
            log.warn("[考勤聚合] 期间 {} 非法，跳过 batchId={}", period, batchId);
            return List.of();
        }
        List<RawAttendance> rows = rawAttendanceMapper.selectList(
            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<RawAttendance>()
                .eq(RawAttendance::getBatchId, batchId));
        if (rows.isEmpty()) {
            return List.of();
        }

        // 同工号聚合（月度汇总一行一人，LinkedHashMap 防御性处理同文件重复行：非空值覆盖）
        Map<String, AttendanceSummarySyncDTO> byCode = new LinkedHashMap<>();
        for (RawAttendance raw : rows) {
            String code = raw.getEmployeeCode() == null ? null : raw.getEmployeeCode().trim();
            if (code == null || code.isEmpty()) {
                continue;
            }
            AttendanceSummarySyncDTO dto = byCode.computeIfAbsent(code, k -> {
                AttendanceSummarySyncDTO d = new AttendanceSummarySyncDTO();
                d.setEmployeeCode(k);
                d.setAttendMonth(attendMonth);
                return d;
            });
            JsonNode extra = readJson(raw.getRawJson());
            dto.setAttendDays(firstNonNull(dto.getAttendDays(), dec(extra, "attendDays")));
            dto.setRestDays(firstNonNull(dto.getRestDays(), dec(extra, "restDays")));
            dto.setLateMinutes(firstNonNull(dto.getLateMinutes(), intOf(extra, "lateMinutes")));
            dto.setMissingCardCount(firstNonNull(dto.getMissingCardCount(), intOf(extra, "missingCardCount")));
            dto.setLateCount(firstNonNull(dto.getLateCount(), raw.getLateCount()));
            dto.setAbsentDays(firstNonNull(dto.getAbsentDays(), raw.getAbsentDays()));
            dto.setLeaveDays(firstNonNull(dto.getLeaveDays(), raw.getLeaveDays()));
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

    /**
     * 读取 JSON 数值字段为 BigDecimal，缺失/非法返回 null。
     * Excel 解析器将单元格统一序列化为字符串（如 "25"），数值/文本节点均需兼容。
     */
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

    /** 读取 JSON 数值字段为 Integer，缺失/非法返回 null（复用 dec 兼容字符串） */
    private Integer intOf(JsonNode node, String field) {
        BigDecimal value = dec(node, field);
        return value == null ? null : value.intValue();
    }

    private <T> T firstNonNull(T a, T b) {
        return a != null ? a : b;
    }
}
