package com.panjia.people.application;

import com.panjia.people.application.dto.EmployeeCreateDTO;
import com.panjia.people.application.dto.ImportResult;
import com.panjia.people.interface_.vo.EmployeeImportVo;
import lombok.extern.slf4j.Slf4j;
import org.apache.fesod.sheet.context.AnalysisContext;
import org.apache.fesod.sheet.event.AnalysisEventListener;
import org.dromara.common.excel.core.ExcelListener;
import org.dromara.common.excel.core.ExcelResult;

import java.util.List;

/**
 * 员工 Excel 导入监听器。
 * <p>
 * 逐行解析 Vo → EmployeeCreateDTO，调 EmployeeImportService 逐条容错。
 */
@Slf4j
public class EmployeeImportListener extends AnalysisEventListener<EmployeeImportVo> implements ExcelListener<EmployeeImportVo> {

    private final EmployeeImportService importService;
    private final ImportResult importResult = new ImportResult();

    public EmployeeImportListener(EmployeeImportService importService) {
        this.importService = importService;
    }

    @Override
    public void invoke(EmployeeImportVo vo, AnalysisContext context) {
        EmployeeCreateDTO dto = new EmployeeCreateDTO();
        dto.setEmployeeCode(vo.getEmployeeCode());
        dto.setName(vo.getName());
        dto.setPhone(vo.getPhone());
        dto.setIdCardNo(vo.getIdCardNo());
        dto.setDeptId(vo.getDeptId());
        dto.setPostId(vo.getPostId());
        dto.setRole(vo.getRole());
        dto.setPartTimeStatus(vo.getPartTimeStatus());
        dto.setHireDate(vo.getHireDate());
        dto.setLevelCode(vo.getLevelCode());
        dto.setSocialInsuranceEnabled(parseBoolean(vo.getSocialInsuranceEnabled()));
        dto.setSocialInsuranceRatio(vo.getSocialInsuranceRatio());
        dto.setHousingFundAmount(vo.getHousingFundAmount());
        dto.setCommercialInsurance(parseBoolean(vo.getCommercialInsurance()));
        dto.setDormitoryEnabled(parseBoolean(vo.getDormitoryEnabled()));
        dto.setRemark(vo.getRemark());
        try {
            importService.importEmployees(List.of(dto));
        } catch (Exception e) {
            log.error("员工导入失败: code={}", vo.getEmployeeCode(), e);
        }
    }

    @Override
    public void doAfterAllAnalysed(AnalysisContext context) {
        // 导入结果已在 importResult 中累积
    }

    @Override
    public ExcelResult<EmployeeImportVo> getExcelResult() {
        return new ExcelResult<>() {
            @Override
            public List<EmployeeImportVo> getList() {
                return null;
            }

            @Override
            public List<String> getErrorList() {
                return null;
            }

            @Override
            public String getAnalysis() {
                int success = importResult.getSuccessCount();
                int fail = importResult.getFailures().size();
                if (fail > 0) {
                    StringBuilder sb = new StringBuilder();
                    sb.append("导入完成：成功 ").append(success).append(" 条，失败 ").append(fail).append(" 条。");
                    for (ImportResult.Failure f : importResult.getFailures()) {
                        sb.append("\n").append(f.getEmployeeCode()).append("：").append(f.getErrorMessage());
                    }
                    return sb.toString();
                }
                return "导入成功 " + success + " 条";
            }
        };
    }

    private boolean parseBoolean(String value) {
        if (value == null) {
            return false;
        }
        String v = value.trim();
        return "是".equals(v) || "true".equalsIgnoreCase(v) || "1".equals(v) || "Y".equalsIgnoreCase(v);
    }
}
