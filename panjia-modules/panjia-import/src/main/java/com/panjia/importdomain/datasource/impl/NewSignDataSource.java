package com.panjia.importdomain.datasource.impl;

import com.panjia.importdomain.datasource.AbstractDataSource;
import com.panjia.importdomain.datasource.ImportContext;
import com.panjia.importdomain.datasource.ParseResult;
import com.panjia.importdomain.domain.ImportIssueType;
import com.panjia.importdomain.domain.ImportSourceType;
import com.panjia.importdomain.domain.raw.RawNewSign;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 贝壳新签数据源（KE_NEW_SIGN）。
 */
@Component
public class NewSignDataSource extends AbstractDataSource {

    @Override
    public ImportSourceType sourceType() {
        return ImportSourceType.KE_NEW_SIGN;
    }

    @Override
    public ParseResult parse(List<Map<String, Object>> rawRows, ImportContext ctx) {
        ParseResult result = new ParseResult();
        for (int i = 0; i < rawRows.size(); i++) {
            Map<String, Object> row = rawRows.get(i);
            int rowNo = i + 1;
            RawNewSign raw = new RawNewSign();
            raw.setBatchId(ctx.getBatchId());
            raw.setRowNo(rowNo);
            raw.setRawJson(toRawJson(row));

            raw.setArriveMonth(str(row, "arriveMonth"));
            raw.setBizType(str(row, "bizType"));
            raw.setOrderNo(str(row, "orderNo"));
            raw.setContractNo(str(row, "contractNo"));
            raw.setRoleSysNo(str(row, "roleSysNo"));
            raw.setRoleName(str(row, "roleName"));
            raw.setRoleType(str(row, "roleType"));
            raw.setShareRatio(decimal(row, "shareRatio"));
            raw.setCurrentReceivable(decimal(row, "currentReceivable"));
            raw.setCurrentReceived(decimal(row, "currentReceived"));

            if (raw.getRoleSysNo() == null || raw.getRoleSysNo().isEmpty()) {
                result.addIssue(issue(rowNo, ImportIssueType.REQUIRED_MISSING,
                    "roleSysNo", null, "贝壳新签：角色系统号必填"));
            }
            result.addRow(raw);
        }
        return result;
    }
}
