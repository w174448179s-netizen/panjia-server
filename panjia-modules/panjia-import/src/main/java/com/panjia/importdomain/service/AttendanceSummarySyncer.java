package com.panjia.importdomain.service;

import com.panjia.contracts.dto.AttendanceSummarySyncDTO;
import com.panjia.contracts.port.PeopleAttendanceSyncPort;
import com.panjia.importdomain.domain.raw.RawAttendance;
import com.panjia.importdomain.mapper.RawAttendanceMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
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
 * 考勤导入 → 员工域月度汇总同步器。
 * <p>
 * 考勤（ATTENDANCE）批次归档（自动归档/手动归档）后调用：
 * 按批次读原始考勤行（一行一人一月），从实体列与 raw_json 提取月度指标，
 * 经 {@link PeopleAttendanceSyncPort}（员工域实现）写入员工域考勤汇总表，
 * 实现"导入即同步"，人事无需再手工登记。
 * <p>
 * 同步失败仅记录错误日志，不阻断导入主流程；重归一化/重复导入场景
 * 由员工域 upsert 语义（同人同月覆盖）保证幂等。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AttendanceSummarySyncer {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final RawAttendanceMapper rawAttendanceMapper;
    /** 端口实现位于员工域；ObjectProvider 允许无员工域的独立部署/测试环境缺省 */
    private final ObjectProvider<PeopleAttendanceSyncPort> syncPortProvider;

    /**
     * 若批次为考勤类型则执行同步（其他类型直接返回）。
     *
     * @param batchId    已归档批次 ID
     * @param sourceType 批次来源类型代码（KE_SIGNED/ATTENDANCE/...）
     * @param period     归属期间（YYYY-MM）
     */
    public void syncIfAttendance(Long batchId, String sourceType, String period) {
        if (!"ATTENDANCE".equals(sourceType)) {
            return;
        }
        PeopleAttendanceSyncPort port = syncPortProvider.getIfAvailable();
        if (port == null) {
            log.warn("[考勤同步] 员工域同步端口不可用，跳过 batchId={}", batchId);
            return;
        }
        try {
            LocalDate attendMonth = parseMonthStart(period);
            if (attendMonth == null) {
                log.warn("[考勤同步] 期间 {} 非法，跳过 batchId={}", period, batchId);
                return;
            }
            List<RawAttendance> rows = rawAttendanceMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<RawAttendance>()
                    .eq(RawAttendance::getBatchId, batchId));
            if (rows.isEmpty()) {
                return;
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

            port.syncAttendanceSummaries(period, new ArrayList<>(byCode.values()));
        } catch (Exception e) {
            // 不阻断导入：导入已归档成功，同步失败可重导入覆盖
            log.error("[考勤同步] batchId={} 期间 {} 同步考勤汇总失败（不阻断导入）", batchId, period, e);
        }
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

    /** 读取 JSON 数值字段为 BigDecimal，缺失/非法返回 null */
    private BigDecimal dec(JsonNode node, String field) {
        JsonNode v = node.path(field);
        if (v.isMissingNode() || v.isNull() || !v.isNumber()) {
            return null;
        }
        try {
            return new BigDecimal(v.asText());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 读取 JSON 数值字段为 Integer，缺失/非法返回 null */
    private Integer intOf(JsonNode node, String field) {
        JsonNode v = node.path(field);
        if (v.isMissingNode() || v.isNull() || !v.isNumber()) {
            return null;
        }
        return v.intValue();
    }

    private <T> T firstNonNull(T a, T b) {
        return a != null ? a : b;
    }
}
