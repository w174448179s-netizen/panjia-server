package com.panjia.importdomain.datasource.impl;

import com.panjia.importdomain.datasource.AbstractDataSource;
import com.panjia.importdomain.datasource.ImportContext;
import com.panjia.importdomain.datasource.ParseResult;
import com.panjia.importdomain.domain.ImportIssueType;
import com.panjia.importdomain.domain.ImportSourceType;
import com.panjia.importdomain.domain.raw.RawEmployee;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 员工主数据数据源（EMPLOYEE）。
 * <p>
 * 不产 NormalizedRecord，归一化阶段直接走 people 域 EmployeeImportSink。
 */
@Component
public class EmployeeDataSource extends AbstractDataSource {

    @Override
    public ImportSourceType sourceType() {
        return ImportSourceType.EMPLOYEE;
    }

    @Override
    public ParseResult parse(List<Map<String, Object>> rawRows, ImportContext ctx) {
        ParseResult result = new ParseResult();
        for (int i = 0; i < rawRows.size(); i++) {
            Map<String, Object> row = rawRows.get(i);
            int rowNo = i + 1;
            RawEmployee raw = new RawEmployee();
            raw.setBatchId(ctx.getBatchId());
            raw.setRowNo(rowNo);
            raw.setRawJson(toRawJson(row));

            raw.setEmployeeCode(str(row, "employeeCode"));
            raw.setName(str(row, "name"));
            raw.setPhone(str(row, "phone"));
            raw.setIdCard(str(row, "idCard"));
            raw.setDeptPath(str(row, "deptPath"));
            raw.setPostNames(str(row, "postNames"));
            raw.setLevel(str(row, "level"));
            raw.setSocialInsured(str(row, "socialInsured"));
            raw.setHousingInsured(str(row, "housingInsured"));
            raw.setCommerceInsurance(decimal(row, "commerceInsurance"));
            raw.setDormitory(str(row, "dormitory"));
            raw.setPartTime(str(row, "partTime"));
            raw.setMaster(str(row, "master"));
            raw.setEntryDate(date(row, "entryDate"));

            if (raw.getEmployeeCode() == null || raw.getEmployeeCode().isEmpty()) {
                result.addIssue(issue(rowNo, ImportIssueType.REQUIRED_MISSING,
                    "employeeCode", null, "员工主数据：工号必填"));
            }
            if (raw.getName() == null || raw.getName().isEmpty()) {
                result.addIssue(issue(rowNo, ImportIssueType.REQUIRED_MISSING,
                    "name", null, "员工主数据：姓名必填"));
            }
            if (raw.getDeptPath() == null || raw.getDeptPath().isEmpty()) {
                result.addIssue(issue(rowNo, ImportIssueType.REQUIRED_MISSING,
                    "deptPath", null, "员工主数据：部门路径必填"));
            }
            result.addRow(raw);
        }
        return result;
    }
}
