package com.panjia.importdomain.datasource.impl;

import com.panjia.importdomain.datasource.SourceKeyGenerator;
import com.panjia.importdomain.domain.ImportSourceType;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 考勤业务唯一键：工号 + 考勤日期。
 */
@Component
public class AttendanceSourceKeyGenerator implements SourceKeyGenerator {

    @Override
    public ImportSourceType sourceType() {
        return ImportSourceType.ATTENDANCE;
    }

    @Override
    public String generate(Map<String, Object> row) {
        String employeeCode = row.get("employeeCode") == null ? "" : row.get("employeeCode").toString().trim();
        String attendDate = row.get("attendDate") == null ? "" : row.get("attendDate").toString().trim();
        if (employeeCode.isEmpty() || attendDate.isEmpty()) {
            return null;
        }
        return employeeCode + "|" + attendDate;
    }
}
