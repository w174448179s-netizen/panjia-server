package com.panjia.importdomain.datasource.impl;

import com.panjia.importdomain.datasource.AbstractDataSource;
import com.panjia.importdomain.datasource.ImportContext;
import com.panjia.importdomain.datasource.ParseResult;
import com.panjia.importdomain.domain.ImportSourceType;
import com.panjia.importdomain.domain.raw.RawAttendance;
import com.panjia.importutil.dto.ParsedRow;
import com.panjia.importutil.dto.ParsedSheet;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 考勤数据源（ATTENDANCE）。
 * <p>
 * V200 起对接钉钉《月度汇总》标准表：一行一人一月，行内没有考勤日期列，
 * 归属月取导入时选择的批次期间并归一为当月 1 日（同时写入 raw_json 供 sourceKey 使用）；
 * 模板映射的姓名/部门/出勤天数等指标随 raw_json 全量归档，必填/类型校验由
 * common-import-util 基础校验器完成，本类只做结构转换。
 */
@Component
public class AttendanceDataSource extends AbstractDataSource {

    @Override
    public ImportSourceType sourceType() {
        return ImportSourceType.ATTENDANCE;
    }

    @Override
    public ParseResult parse(ParsedSheet sheet, ImportContext ctx) {
        ParseResult result = new ParseResult();
        LocalDate monthStart = parseMonthStart(ctx.getPeriod());
        for (ParsedRow row : sheet.getRows()) {
            RawAttendance raw = new RawAttendance();
            raw.setBatchId(ctx.getBatchId());
            raw.setRowNo(row.getRowNo());

            // raw_json 保留模板映射的全部指标列，并补入按归属月推导的考勤日期，
            // 使归一化阶段 sourceKey（工号|考勤日期）与员工号提取逻辑保持不变
            Map<String, Object> rawJson = toRawJsonMap(row);
            if (monthStart != null) {
                rawJson.put("attendDate", monthStart.toString());
            }
            raw.setRawJson(writeJson(rawJson));

            raw.setEmployeeCode(str(row, "employeeCode"));
            raw.setAttendDate(monthStart);
            raw.setLateCount(integer(row, "lateCount"));
            raw.setAbsentDays(decimal(row, "absentDays"));
            // 请假天数 = 事假 + 病假（合计参与算薪扣款）
            BigDecimal personalLeave = decimal(row, "personalLeaveDays");
            BigDecimal sickLeave = decimal(row, "sickLeaveDays");
            BigDecimal leaveDays = sumNullable(personalLeave, sickLeave);
            raw.setLeaveDays(leaveDays);
            // 补入 raw_json，供归一化阶段消费（extraJson）与未来按日拆分留痕
            rawJson.put("personalLeaveDays", personalLeave);
            rawJson.put("sickLeaveDays", sickLeave);
            rawJson.put("leaveDays", leaveDays);
            raw.setRawJson(writeJson(rawJson));

            // 从未映射列（Excel Q+ 每日考勤结果列）解析每日状态
            Map<String, String> unmapped = row.getUnmappedRawValues();
            if (unmapped != null && !unmapped.isEmpty() && monthStart != null) {
                Map<String, String> dailyStatus = new LinkedHashMap<>();
                YearMonth ym = YearMonth.from(monthStart);
                for (Map.Entry<String, String> entry : unmapped.entrySet()) {
                    LocalDate date = parseDailyHeader(entry.getKey(), ym);
                    if (date != null) {
                        dailyStatus.put(date.toString(), entry.getValue());
                    }
                }
                if (!dailyStatus.isEmpty()) {
                    rawJson.put("dailyStatus", dailyStatus);
                    raw.setRawJson(writeJson(rawJson));
                }
            }

            result.addRow(raw);
        }
        return result;
    }

    /** 归属月（YYYY-MM）→ 当月 1 日；期间缺失/非法时返回 null（不阻断解析） */
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

    /** 两个 BigDecimal 求和，全空返回 null（不写 0，避免与"未填写"混淆） */
    private BigDecimal sumNullable(BigDecimal a, BigDecimal b) {
        if (a == null && b == null) {
            return null;
        }
        return (a == null ? BigDecimal.ZERO : a).add(b == null ? BigDecimal.ZERO : b);
    }

    /** 钉钉列头日期解析模式：「N日」「M/D」「MM-DD」「YYYY-MM-DD」 */
    private static final Pattern DAY_PATTERN = Pattern.compile("(\\d{1,2})日");
    private static final Pattern SLASH_PATTERN = Pattern.compile("(\\d{1,2})/(\\d{1,2})");
    private static final Pattern DASH_PATTERN = Pattern.compile("(\\d{1,2})-(\\d{1,2})");

    /**
     * 解析钉钉月度汇总 Excel 第 4 行的每日日期列头为 LocalDate。
     * <p>
     * 支持格式：{@code N日}（如"1日"）、{@code M/D}（如"10/1"）、
     * {@code MM-DD}（如"10-01"）、{@code YYYY-MM-DD}（如"2026-10-01"）。
     * 用 period 的年月补全缺失部分；解析失败返回 null（列跳过）。
     *
     * @param header 列头文本
     * @param period 归属月（提供年/月上下文）
     * @return 解析出的日期，失败返回 null
     */
    private LocalDate parseDailyHeader(String header, YearMonth period) {
        if (header == null || header.isBlank()) {
            return null;
        }
        String h = header.trim();
        try {
            // "2026-10-01" 标准格式
            return LocalDate.parse(h);
        } catch (DateTimeParseException ignored) {
        }
        // "N日" 格式：取日，用 period 的年月
        Matcher m = DAY_PATTERN.matcher(h);
        if (m.find()) {
            int day = Integer.parseInt(m.group(1));
            return safeDate(period, day);
        }
        // "M/D" 格式：月/日
        m = SLASH_PATTERN.matcher(h);
        if (m.find()) {
            int month = Integer.parseInt(m.group(1));
            int day = Integer.parseInt(m.group(2));
            return safeDate(YearMonth.of(period.getYear(), month), day);
        }
        // "MM-DD" 格式
        m = DASH_PATTERN.matcher(h);
        if (m.find()) {
            int month = Integer.parseInt(m.group(1));
            int day = Integer.parseInt(m.group(2));
            return safeDate(YearMonth.of(period.getYear(), month), day);
        }
        return null;
    }

    /** 安全构造日期：日超出月份范围时返回 null */
    private LocalDate safeDate(YearMonth ym, int day) {
        if (day < 1 || day > ym.lengthOfMonth()) {
            return null;
        }
        return ym.atDay(day);
    }
}
