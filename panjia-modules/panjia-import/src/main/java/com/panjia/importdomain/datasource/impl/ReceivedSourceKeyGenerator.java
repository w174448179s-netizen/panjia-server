package com.panjia.importdomain.datasource.impl;

import com.panjia.importdomain.datasource.SourceKeyGenerator;
import com.panjia.importdomain.domain.ImportSourceType;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 贝壳实收业务唯一键：订单号 + 合同号 + 角色人系统号 + 角色类型。
 * <p>
 * 到账贡献明细表无费用项列，一行 = 一个角色人在一张合同上的当月到账贡献；
 * 到账月由批次归属期（period）承载，不参与键组合（同月内唯一即可，
 * 跨月重导由批次 supersede 冲销重建）。
 */
@Component
public class ReceivedSourceKeyGenerator implements SourceKeyGenerator {

    @Override
    public ImportSourceType sourceType() {
        return ImportSourceType.KE_RECEIVED;
    }

    @Override
    public String generate(Map<String, Object> row) {
        String orderNo = row.get("orderNo") == null ? "" : row.get("orderNo").toString().trim();
        String contractNo = row.get("contractNo") == null ? "" : row.get("contractNo").toString().trim();
        String roleSysNo = row.get("roleSysNo") == null ? "" : row.get("roleSysNo").toString().trim();
        String roleType = row.get("roleType") == null ? "" : row.get("roleType").toString().trim();
        if (orderNo.isEmpty() && contractNo.isEmpty() && roleSysNo.isEmpty()) {
            return null;
        }
        return orderNo + "|" + contractNo + "|" + roleSysNo + "|" + roleType;
    }
}
