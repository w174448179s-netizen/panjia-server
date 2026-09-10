package com.panjia.importdomain.datasource.impl;

import com.panjia.importdomain.datasource.AbstractDataSource;
import com.panjia.importdomain.datasource.ImportContext;
import com.panjia.importdomain.datasource.ParseResult;
import com.panjia.importdomain.domain.ImportIssueType;
import com.panjia.importdomain.domain.ImportSourceType;
import com.panjia.importdomain.domain.raw.RawManual;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 手工录入数据源（OTHERS/MANUAL）。
 */
@Component
public class ManualDataSource extends AbstractDataSource {

    @Override
    public ImportSourceType sourceType() {
        return ImportSourceType.OTHERS;
    }

    @Override
    public ParseResult parse(List<Map<String, Object>> rawRows, ImportContext ctx) {
        ParseResult result = new ParseResult();
        for (int i = 0; i < rawRows.size(); i++) {
            Map<String, Object> row = rawRows.get(i);
            int rowNo = i + 1;
            RawManual raw = new RawManual();
            raw.setBatchId(ctx.getBatchId());
            raw.setRowNo(rowNo);
            raw.setRawJson(toRawJson(row));

            raw.setEmployeeCode(str(row, "employeeCode"));
            raw.setItemType(str(row, "itemType"));
            raw.setAmount(decimal(row, "amount"));
            raw.setReason(str(row, "reason"));

            if (raw.getEmployeeCode() == null || raw.getEmployeeCode().isEmpty()) {
                result.addIssue(issue(rowNo, ImportIssueType.REQUIRED_MISSING,
                    "employeeCode", null, "手工录入：工号必填"));
            }
            if (raw.getItemType() == null || raw.getItemType().isEmpty()) {
                result.addIssue(issue(rowNo, ImportIssueType.REQUIRED_MISSING,
                    "itemType", null, "手工录入：项目类型必填"));
            }
            result.addRow(raw);
        }
        return result;
    }
}
