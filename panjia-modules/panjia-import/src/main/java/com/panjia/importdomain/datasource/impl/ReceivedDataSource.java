package com.panjia.importdomain.datasource.impl;

import com.panjia.importdomain.datasource.AbstractDataSource;
import com.panjia.importdomain.datasource.ImportContext;
import com.panjia.importdomain.datasource.ParseResult;
import com.panjia.importdomain.domain.ImportSourceType;
import com.panjia.importdomain.domain.raw.RawReceived;
import com.panjia.importutil.dto.ParsedRow;
import com.panjia.importutil.dto.ParsedSheet;
import org.springframework.stereotype.Component;

/**
 * 贝壳实收数据源（KE_RECEIVED，理房通·经纪人到账贡献明细表）。
 * <p>
 * 必填/类型校验由 common-import-util 基础校验器完成，本类只做结构转换；
 * 负数到账金额（退单负实收）为合法业务值，金额列不设非负校验。
 */
@Component
public class ReceivedDataSource extends AbstractDataSource {

    @Override
    public ImportSourceType sourceType() {
        return ImportSourceType.KE_RECEIVED;
    }

    @Override
    public ParseResult parse(ParsedSheet sheet, ImportContext ctx) {
        ParseResult result = new ParseResult();
        for (ParsedRow row : sheet.getRows()) {
            RawReceived raw = new RawReceived();
            raw.setBatchId(ctx.getBatchId());
            raw.setRowNo(row.getRowNo());
            raw.setRawJson(toRawJson(row));

            raw.setArriveMonth(str(row, "arriveMonth"));
            raw.setBizType(str(row, "bizType"));
            raw.setOrderNo(str(row, "orderNo"));
            raw.setContractNo(str(row, "contractNo"));
            raw.setSignDate(str(row, "signDate"));
            raw.setPropertyAddress(str(row, "propertyAddress"));
            raw.setRoleSysNo(str(row, "roleSysNo"));
            raw.setRoleName(str(row, "roleName"));
            raw.setRoleType(str(row, "roleType"));
            raw.setShareRatio(decimal(row, "shareRatio"));
            raw.setRoleArrivalAmount(decimal(row, "roleArrivalAmount"));
            raw.setContractArrivalAmount(decimal(row, "contractArrivalAmount"));
            raw.setCurrentReceivable(decimal(row, "currentReceivable"));
            raw.setCurrentReceived(decimal(row, "currentReceived"));
            raw.setDeptCode(str(row, "deptCode"));
            raw.setDeptName(str(row, "deptName"));
            raw.setStoreCode(str(row, "storeCode"));
            raw.setStoreName(str(row, "storeName"));
            raw.setRemark(str(row, "remark"));

            result.addRow(raw);
        }
        return result;
    }
}
