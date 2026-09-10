package com.panjia.importdomain.datasource.impl;

import com.panjia.importdomain.datasource.SourceKeyGenerator;
import com.panjia.importdomain.domain.ImportSourceType;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 贝壳新签业务唯一键：订单号 + 合同号 + 角色系统号。
 */
@Component
public class NewSignSourceKeyGenerator implements SourceKeyGenerator {

    @Override
    public ImportSourceType sourceType() {
        return ImportSourceType.KE_NEW_SIGN;
    }

    @Override
    public String generate(Map<String, Object> row) {
        String orderNo = row.get("orderNo") == null ? "" : row.get("orderNo").toString().trim();
        String contractNo = row.get("contractNo") == null ? "" : row.get("contractNo").toString().trim();
        String roleSysNo = row.get("roleSysNo") == null ? "" : row.get("roleSysNo").toString().trim();
        if (orderNo.isEmpty() && contractNo.isEmpty()) {
            return null;
        }
        return orderNo + "|" + contractNo + "|" + roleSysNo;
    }
}
