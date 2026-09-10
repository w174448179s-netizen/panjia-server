package com.panjia.importdomain.datasource.impl;

import com.panjia.importdomain.datasource.AbstractDataSource;
import com.panjia.importdomain.datasource.ImportContext;
import com.panjia.importdomain.datasource.ParseResult;
import com.panjia.importdomain.domain.ImportSourceType;
import com.panjia.importdomain.domain.raw.RawSigned;
import com.panjia.importutil.dto.ParsedRow;
import com.panjia.importutil.dto.ParsedSheet;
import org.springframework.stereotype.Component;

/**
 * 贝壳结佣数据源（KE_SIGNED）。
 * <p>
 * 必填/类型校验由 common-import-util 基础校验器完成，本类只做结构转换。
 */
@Component
public class SignedDataSource extends AbstractDataSource {

    @Override
    public ImportSourceType sourceType() {
        return ImportSourceType.KE_SIGNED;
    }

    @Override
    public ParseResult parse(ParsedSheet sheet, ImportContext ctx) {
        ParseResult result = new ParseResult();
        for (ParsedRow row : sheet.getRows()) {
            RawSigned raw = new RawSigned();
            raw.setBatchId(ctx.getBatchId());
            raw.setRowNo(row.getRowNo());
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

            result.addRow(raw);
        }
        return result;
    }
}
