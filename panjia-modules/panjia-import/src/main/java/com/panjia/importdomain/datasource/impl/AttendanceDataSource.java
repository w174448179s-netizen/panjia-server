package com.panjia.importdomain.datasource.impl;

import com.panjia.importdomain.datasource.AbstractDataSource;
import com.panjia.importdomain.datasource.ImportContext;
import com.panjia.importdomain.datasource.ParseResult;
import com.panjia.importdomain.domain.ImportIssueType;
import com.panjia.importdomain.domain.ImportSourceType;
import com.panjia.importdomain.domain.raw.RawAttendance;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 考勤数据源（ATTENDANCE）。
 */
@Component
public class AttendanceDataSource extends AbstractDataSource {

    @Override
    public ImportSourceType sourceType() {
        return ImportSourceType.ATTENDANCE;
    }

    @Override
    public ParseResult parse(List<Map<String, Object>> rawRows, ImportContext ctx) {
        ParseResult result = new ParseResult();
        for (int i = 0; i < rawRows.size(); i++) {
            Map<String, Object> row = rawRows.get(i);
            int rowNo = i + 1;
            RawAttendance raw = new RawAttendance();
            raw.setBatchId(ctx.getBatchId());
            raw.setRowNo(rowNo);
            raw.setRawJson(toRawJson(row));

            raw.setEmployeeCode(str(row, "employeeCode"));
            raw.setAttendDate(date(row, "attendDate"));
            raw.setLateCount(intVal(row, "lateCount"));
            raw.setAbsentDays(decimal(row, "absentDays"));
            raw.setLeaveAmount(decimal(row, "leaveAmount"));

            if (raw.getEmployeeCode() == null || raw.getEmployeeCode().isEmpty()) {
                result.addIssue(issue(rowNo, ImportIssueType.REQUIRED_MISSING,
                    "employeeCode", null, "考勤：工号必填"));
            }
            if (raw.getAttendDate() == null) {
                result.addIssue(issue(rowNo, ImportIssueType.COLUMN_TYPE_ERR,
                    "attendDate", str(row, "attendDate"), "考勤日期格式错误"));
            }
            result.addRow(raw);
        }
        return result;
    }
}
