package com.panjia.importdomain.datasource.impl;

import com.panjia.importdomain.datasource.SourceKeyGenerator;
import com.panjia.importdomain.domain.ImportSourceType;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 积分业务唯一键：工号 + 积分日期。
 */
@Component
public class PointsSourceKeyGenerator implements SourceKeyGenerator {

    @Override
    public ImportSourceType sourceType() {
        return ImportSourceType.POINTS;
    }

    @Override
    public String generate(Map<String, Object> row) {
        String employeeCode = row.get("employeeCode") == null ? "" : row.get("employeeCode").toString().trim();
        String pointDate = row.get("pointDate") == null ? "" : row.get("pointDate").toString().trim();
        if (employeeCode.isEmpty() || pointDate.isEmpty()) {
            return null;
        }
        return employeeCode + "|" + pointDate;
    }
}
