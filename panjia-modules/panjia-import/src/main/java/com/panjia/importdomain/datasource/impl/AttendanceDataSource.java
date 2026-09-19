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
import java.util.Map;

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
}
