package com.panjia.importdomain.datasource.impl;

import com.panjia.importdomain.datasource.AbstractDataSource;
import com.panjia.importdomain.datasource.ImportContext;
import com.panjia.importdomain.datasource.ParseResult;
import com.panjia.importdomain.domain.ImportIssueType;
import com.panjia.importdomain.domain.ImportSourceType;
import com.panjia.importdomain.domain.raw.RawPoints;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 积分数据源（POINTS）。
 */
@Component
public class PointsDataSource extends AbstractDataSource {

    @Override
    public ImportSourceType sourceType() {
        return ImportSourceType.POINTS;
    }

    @Override
    public ParseResult parse(List<Map<String, Object>> rawRows, ImportContext ctx) {
        ParseResult result = new ParseResult();
        for (int i = 0; i < rawRows.size(); i++) {
            Map<String, Object> row = rawRows.get(i);
            int rowNo = i + 1;
            RawPoints raw = new RawPoints();
            raw.setBatchId(ctx.getBatchId());
            raw.setRowNo(rowNo);
            raw.setRawJson(toRawJson(row));

            raw.setEmployeeCode(str(row, "employeeCode"));
            raw.setPointDate(date(row, "pointDate"));
            raw.setScore(decimal(row, "score"));
            raw.setViolationCount(intVal(row, "violationCount"));

            if (raw.getEmployeeCode() == null || raw.getEmployeeCode().isEmpty()) {
                result.addIssue(issue(rowNo, ImportIssueType.REQUIRED_MISSING,
                    "employeeCode", null, "积分：工号必填"));
            }
            if (raw.getPointDate() == null) {
                result.addIssue(issue(rowNo, ImportIssueType.COLUMN_TYPE_ERR,
                    "pointDate", str(row, "pointDate"), "积分日期格式错误"));
            }
            result.addRow(raw);
        }
        return result;
    }
}
