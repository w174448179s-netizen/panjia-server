package com.panjia.importdomain.datasource.impl;

import com.panjia.importdomain.datasource.AbstractDataSource;
import com.panjia.importdomain.datasource.ImportContext;
import com.panjia.importdomain.datasource.ParseResult;
import com.panjia.importdomain.domain.ImportSourceType;
import com.panjia.importdomain.domain.raw.RawPoints;
import com.panjia.importutil.dto.ParsedRow;
import com.panjia.importutil.dto.ParsedSheet;
import org.springframework.stereotype.Component;

/**
 * 积分数据源（POINTS）。
 * <p>
 * 必填/类型校验由 common-import-util 基础校验器完成，本类只做结构转换。
 */
@Component
public class PointsDataSource extends AbstractDataSource {

    @Override
    public ImportSourceType sourceType() {
        return ImportSourceType.POINTS;
    }

    @Override
    public ParseResult parse(ParsedSheet sheet, ImportContext ctx) {
        ParseResult result = new ParseResult();
        for (ParsedRow row : sheet.getRows()) {
            RawPoints raw = new RawPoints();
            raw.setBatchId(ctx.getBatchId());
            raw.setRowNo(row.getRowNo());
            raw.setRawJson(toRawJson(row));

            raw.setEmployeeCode(str(row, "employeeCode"));
            raw.setPointDate(date(row, "pointDate"));
            raw.setScore(decimal(row, "score"));
            raw.setViolationCount(integer(row, "violationCount"));

            result.addRow(raw);
        }
        return result;
    }
}
