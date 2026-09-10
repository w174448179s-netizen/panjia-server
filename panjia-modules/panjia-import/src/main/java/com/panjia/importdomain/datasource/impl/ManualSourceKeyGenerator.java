package com.panjia.importdomain.datasource.impl;

import com.panjia.importdomain.datasource.SourceKeyGenerator;
import com.panjia.importdomain.domain.ImportSourceType;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 手工录入业务唯一键：工号 + 项目类型 + 行号（无天然唯一键时用 rowNo 兜底）。
 */
@Component
public class ManualSourceKeyGenerator implements SourceKeyGenerator {

    @Override
    public ImportSourceType sourceType() {
        return ImportSourceType.OTHERS;
    }

    @Override
    public String generate(Map<String, Object> row) {
        String employeeCode = row.get("employeeCode") == null ? "" : row.get("employeeCode").toString().trim();
        String itemType = row.get("itemType") == null ? "" : row.get("itemType").toString().trim();
        String rowNo = row.get("rowNo") == null ? "" : row.get("rowNo").toString().trim();
        if (employeeCode.isEmpty()) {
            return null;
        }
        return employeeCode + "|" + itemType + "|" + rowNo;
    }
}
