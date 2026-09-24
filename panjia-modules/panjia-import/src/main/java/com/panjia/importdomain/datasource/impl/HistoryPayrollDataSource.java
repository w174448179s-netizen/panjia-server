package com.panjia.importdomain.datasource.impl;

import com.panjia.importdomain.datasource.AbstractDataSource;
import com.panjia.importdomain.datasource.ImportContext;
import com.panjia.importdomain.datasource.ParseResult;
import com.panjia.importdomain.domain.ImportIssueType;
import com.panjia.importdomain.domain.ImportSourceType;
import com.panjia.importdomain.domain.raw.RawAttendance;
import com.panjia.importdomain.domain.raw.RawPayroll;
import com.panjia.importdomain.domain.raw.RawPoints;
import com.panjia.importdomain.domain.raw.RawSigned;
import com.panjia.importutil.convert.TypeConverter;
import com.panjia.importutil.dto.ParsedRow;
import com.panjia.importutil.dto.ParsedSheet;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 历史工资数据源（HISTORY_PAYROLL 标准管线）：多模板循环解析，一模板一 sheet，
 * 按 {@link ImportContext#getTemplateCode()} 分流：
 * <ul>
 *   <li>工资族（HIST_PAYROLL/HIST_DIRECTOR/HIST_MANAGER/HIST_HR_PATCH/
 *       HIST_PERF_LEFT/HIST_PERF_RIGHT）→ {@link RawPayroll}，sheetKind 标记来源；</li>
 *   <li>考勤口径（HIST_HR_ATT）→ {@link RawAttendance}：月度汇总行，
 *       attendDate=归属月首日，迟到次数由「考勤详情」正则（迟到N次）解析；</li>
 *   <li>积分口径（HIST_SCORE）→ {@link RawPoints}：月度总量行，pointDate=归属月首日；</li>
 *   <li>业绩（HIST_NEW_SIGN/HIST_COMMISSION）→ {@link RawSigned}：
 *       raw_json.recordType=HIST_EXPECT/HIST_REAL，金额为「85后」折算后口径，
 *       反折算在归一化期执行。</li>
 * </ul>
 * 历史表无工号列：不写 employeeCode，姓名→工号富化在归一化期由 ImportEngine
 * 经 PeopleQueryPort 批量完成（DataSource SPI 不做跨域查询）。
 * 历史列全为 STRING（脏数据容忍），金额解析宽容失败返回 null。
 */
@Slf4j
@Component
public class HistoryPayrollDataSource extends AbstractDataSource {

    /** 考勤详情文本中的迟到次数（如「迟到3次」） */
    private static final Pattern LATE_COUNT_RE = Pattern.compile("迟到(\\d+)次");

    @Override
    public ImportSourceType sourceType() {
        return ImportSourceType.HISTORY_PAYROLL;
    }

    @Override
    public ParseResult parse(ParsedSheet sheet, ImportContext ctx) {
        ParseResult result = new ParseResult();
        String kind = sheetKindOf(ctx.getTemplateCode());
        if (kind == null) {
            result.addIssue(issue(null, ImportIssueType.COLUMN_TYPE_ERR, "templateCode",
                ctx.getTemplateCode(), "未知的历史工资模板: " + ctx.getTemplateCode()));
            return result;
        }
        switch (kind) {
            case "ATT" -> parseAttendance(sheet, ctx, result);
            case "PTS" -> parsePoints(sheet, ctx, result);
            case "EXPECT", "REAL" -> parseSigned(sheet, ctx, result, kind);
            default -> parsePayroll(sheet, ctx, result, kind);
        }
        return result;
    }

    /** 模板 code → 分流标识；null=未知模板 */
    private String sheetKindOf(String templateCode) {
        if (templateCode == null) {
            return null;
        }
        return switch (templateCode) {
            case "HIST_PAYROLL" -> "WAGE";
            case "HIST_DIRECTOR" -> "DIRECTOR";
            case "HIST_MANAGER" -> "MANAGER";
            case "HIST_HR_PATCH" -> "HR";
            case "HIST_PERF_LEFT" -> "PERF_LEFT";
            case "HIST_PERF_RIGHT" -> "PERF_RIGHT";
            case "HIST_HR_ATT" -> "ATT";
            case "HIST_SCORE" -> "PTS";
            case "HIST_NEW_SIGN" -> "EXPECT";
            case "HIST_COMMISSION" -> "REAL";
            default -> null;
        };
    }

    /** 工资族行 → RawPayroll（sheetKind 区分来源，全字段进 raw_json） */
    private void parsePayroll(ParsedSheet sheet, ImportContext ctx, ParseResult result, String kind) {
        for (ParsedRow row : sheet.getRows()) {
            RawPayroll raw = new RawPayroll();
            raw.setBatchId(ctx.getBatchId());
            raw.setRowNo(row.getRowNo());
            Map<String, Object> json = toRawJsonMap(row);
            json.put("sheetKind", kind);
            raw.setRawJson(writeJson(json));
            raw.setEmployeeName(str(row, "employeeName"));
            raw.setSheetKind(kind);
            result.addRow(raw);
        }
    }

    /** 考勤口径行 → RawAttendance（月度汇总行：attendDate=归属月首日，迟到次数正则解析） */
    private void parseAttendance(ParsedSheet sheet, ImportContext ctx, ParseResult result) {
        LocalDate monthStart = monthStartOf(ctx.getPeriod());
        for (ParsedRow row : sheet.getRows()) {
            RawAttendance raw = new RawAttendance();
            raw.setBatchId(ctx.getBatchId());
            raw.setRowNo(row.getRowNo());
            Map<String, Object> json = toRawJsonMap(row);
            json.put("sheetKind", "ATT");
            Integer lateCount = parseLateCount(str(row, "attendanceDetail"));
            if (lateCount != null) {
                json.put("lateCount", String.valueOf(lateCount));
            }
            if (monthStart != null) {
                json.put("attendDate", monthStart.toString());
            }
            raw.setRawJson(writeJson(json));
            raw.setAttendDate(monthStart);
            raw.setLateCount(lateCount);
            raw.setLeaveAmount(dec(row, "leaveAmount"));
            result.addRow(raw);
        }
    }

    /** 积分口径行 → RawPoints（月度总量行：pointDate=归属月首日，score=总积分） */
    private void parsePoints(ParsedSheet sheet, ImportContext ctx, ParseResult result) {
        LocalDate monthStart = monthStartOf(ctx.getPeriod());
        for (ParsedRow row : sheet.getRows()) {
            RawPoints raw = new RawPoints();
            raw.setBatchId(ctx.getBatchId());
            raw.setRowNo(row.getRowNo());
            Map<String, Object> json = toRawJsonMap(row);
            json.put("sheetKind", "PTS");
            if (monthStart != null) {
                json.put("pointDate", monthStart.toString());
            }
            raw.setRawJson(writeJson(json));
            raw.setPointDate(monthStart);
            raw.setScore(dec(row, "score"));
            result.addRow(raw);
        }
    }

    /** 业绩行 → RawSigned（recordType 口径标记进 raw_json，签约人=姓名待归一化匹配） */
    private void parseSigned(ParsedSheet sheet, ImportContext ctx, ParseResult result, String kind) {
        boolean real = "REAL".equals(kind);
        for (ParsedRow row : sheet.getRows()) {
            RawSigned raw = new RawSigned();
            raw.setBatchId(ctx.getBatchId());
            raw.setRowNo(row.getRowNo());
            Map<String, Object> json = toRawJsonMap(row);
            json.put("recordType", real ? "HIST_REAL" : "HIST_EXPECT");
            json.put("sheetKind", real ? "COMMISSION" : "NEW_SIGN");
            raw.setRawJson(writeJson(json));
            raw.setRoleName(str(row, "employeeName"));
            raw.setContractNo(str(row, "contractNo"));
            raw.setBizType(str(row, "bizType"));
            raw.setRoleType(str(row, "roleType"));
            raw.setShareRatio(dec(row, "shareRatio"));
            BigDecimal amount = dec(row, "amount85");
            if (real) {
                raw.setCurrentReceived(amount);
            } else {
                raw.setCurrentReceivable(amount);
            }
            result.addRow(raw);
        }
    }

    /** 「考勤详情」文本解析迟到次数（迟到N次）；无匹配返回 null */
    private Integer parseLateCount(String detail) {
        if (detail == null || detail.isEmpty()) {
            return null;
        }
        Matcher m = LATE_COUNT_RE.matcher(detail);
        if (m.find()) {
            try {
                return Integer.valueOf(m.group(1));
            } catch (NumberFormatException ignore) {
                // falls through
            }
        }
        return null;
    }

    /** 归属期间（YYYY-MM）→ 当月 1 日；非法返回 null */
    private LocalDate monthStartOf(String period) {
        if (period == null || period.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(period.trim() + "-01");
        } catch (DateTimeParseException e) {
            log.warn("[历史工资] 期间非法，考勤/积分行日期置空: {}", period);
            return null;
        }
    }

    /**
     * 宽容数值解析：历史模板列全为 STRING（脏数据容忍，如「不考核」文本），
     * 空/非法文本返回 null。兼容百分号/千分位（TypeConverter.parseDecimal）。
     */
    private BigDecimal dec(ParsedRow row, String field) {
        String s = str(row, field);
        if (s == null || s.isEmpty()) {
            return null;
        }
        try {
            return TypeConverter.parseDecimal(s);
        } catch (Exception e) {
            return null;
        }
    }
}
