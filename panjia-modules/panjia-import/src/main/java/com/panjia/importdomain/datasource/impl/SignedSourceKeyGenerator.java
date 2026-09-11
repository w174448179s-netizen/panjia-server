package com.panjia.importdomain.datasource.impl;

import com.panjia.importdomain.datasource.SourceKeyGenerator;
import com.panjia.importdomain.domain.ImportSourceType;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 贝壳结佣业务唯一键：订单号 + 合同号 + 角色系统号 + 费用项 + 角色类型。
 * <p>
 * 同一订单/合同上的「居间服务费」「贷款服务费」等费用项各自一行；同一费用项下
 * 不同角色类型（考核陪签、房源录入人、维护人、推广人等）又各自一行；角色人系统号
 * 仅指向出单/主导角色人并不区分多分账角色。五个维度组合才落到业务上唯一一行结算明细。
 * 若仅用 (orderNo, contractNo, roleSysNo) 生成 sourceKey，会因为分账角色多行复用
 * 同列而导致 uk_norm_source_key 冲突。
 */
@Component
public class SignedSourceKeyGenerator implements SourceKeyGenerator {

    @Override
    public ImportSourceType sourceType() {
        return ImportSourceType.KE_SIGNED;
    }

    @Override
    public String generate(Map<String, Object> row) {
        String orderNo = row.get("orderNo") == null ? "" : row.get("orderNo").toString().trim();
        String contractNo = row.get("contractNo") == null ? "" : row.get("contractNo").toString().trim();
        String roleSysNo = row.get("roleSysNo") == null ? "" : row.get("roleSysNo").toString().trim();
        String feeItem = row.get("feeItem") == null ? "" : row.get("feeItem").toString().trim();
        String roleType = row.get("roleType") == null ? "" : row.get("roleType").toString().trim();
        if (orderNo.isEmpty() && contractNo.isEmpty() && feeItem.isEmpty()) {
            return null;
        }
        return orderNo + "|" + contractNo + "|" + roleSysNo + "|" + feeItem + "|" + roleType;
    }
}
