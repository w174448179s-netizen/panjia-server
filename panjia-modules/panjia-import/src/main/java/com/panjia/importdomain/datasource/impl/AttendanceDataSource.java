package com.panjia.importdomain.datasource.impl;

import com.panjia.importdomain.datasource.AbstractDataSource;
import com.panjia.importdomain.datasource.ImportContext;
import com.panjia.importdomain.datasource.ParseResult;
import com.panjia.importdomain.domain.ImportSourceType;
import com.panjia.importdomain.domain.raw.RawAttendance;
import com.panjia.importutil.dto.ParsedRow;
import com.panjia.importutil.dto.ParsedSheet;
import org.springframework.stereotype.Component;

/**
 * 考勤数据源（ATTENDANCE）。
 * <p>
 * 必填/类型校验由 common-import-util 基础校验器完成，本类只做结构转换。
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
        for (ParsedRow row : sheet.getRows()) {
            RawAttendance raw = new RawAttendance();
            raw.setBatchId(ctx.getBatchId());
            raw.setRowNo(row.getRowNo());
            raw.setRawJson(toRawJson(row));

            raw.setEmployeeCode(str(row, "employeeCode"));
            raw.setAttendDate(date(row, "attendDate"));
            raw.setLateCount(integer(row, "lateCount"));
            raw.setAbsentDays(decimal(row, "absentDays"));

            result.addRow(raw);
        }
        return result;
    }
}
