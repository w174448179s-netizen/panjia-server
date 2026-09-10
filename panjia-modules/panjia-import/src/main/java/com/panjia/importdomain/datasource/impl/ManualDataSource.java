package com.panjia.importdomain.datasource.impl;

import com.panjia.importdomain.datasource.AbstractDataSource;
import com.panjia.importdomain.datasource.ImportContext;
import com.panjia.importdomain.datasource.ParseResult;
import com.panjia.importdomain.domain.ImportSourceType;
import com.panjia.importdomain.domain.raw.RawManual;
import com.panjia.importutil.dto.ParsedRow;
import com.panjia.importutil.dto.ParsedSheet;
import org.springframework.stereotype.Component;

/**
 * 手工录入数据源（OTHERS/MANUAL，其他费用）。
 * <p>
 * 必填/类型校验由 common-import-util 基础校验器完成，本类只做结构转换。
 */
@Component
public class ManualDataSource extends AbstractDataSource {

    @Override
    public ImportSourceType sourceType() {
        return ImportSourceType.OTHERS;
    }

    @Override
    public ParseResult parse(ParsedSheet sheet, ImportContext ctx) {
        ParseResult result = new ParseResult();
        for (ParsedRow row : sheet.getRows()) {
            RawManual raw = new RawManual();
            raw.setBatchId(ctx.getBatchId());
            raw.setRowNo(row.getRowNo());
            raw.setRawJson(toRawJson(row));

            raw.setEmployeeCode(str(row, "employeeCode"));
            raw.setItemType(str(row, "itemType"));
            raw.setAmount(decimal(row, "amount"));
            raw.setReason(str(row, "reason"));

            result.addRow(raw);
        }
        return result;
    }
}
