package com.panjia.people.interface_.converter;

import com.panjia.people.application.dto.EmployeeCreateDTO;
import com.panjia.people.application.dto.EmployeeDTO;
import com.panjia.people.application.dto.EmployeeUpdateDTO;
import com.panjia.people.domain.Employee;
import com.panjia.people.domain.EmployeeLevel;
import com.panjia.people.domain.EmployeeRoleEnum;
import com.panjia.people.domain.EmployeeStatusEnum;
import com.panjia.people.domain.PartTimeStatusEnum;

/**
 * 员工 Domain ↔ DTO 转换器。
 */
public final class EmployeeConverter {

    private EmployeeConverter() {
        // 工具类，禁止实例化
    }

    /**
     * 创建 DTO → 领域对象（初始状态 ACTIVE / FULL_TIME 默认值）。
     *
     * @param dto 创建 DTO
     * @return 未持久化的员工聚合根
     */
    public static Employee toDomain(EmployeeCreateDTO dto) {
        Employee employee = new Employee();
        employee.setUserId(dto.getUserId());
        employee.setEmployeeCode(dto.getEmployeeCode());
        employee.setName(dto.getName());
        employee.setPhone(dto.getPhone());
        employee.setIdCardNo(dto.getIdCardNo());
        employee.setDeptId(dto.getDeptId());
        employee.setPostId(dto.getPostId());
        employee.setRole(EmployeeRoleEnum.valueOf(dto.getRole()));
        employee.setPartTimeStatus(dto.getPartTimeStatus() == null
            ? PartTimeStatusEnum.FULL_TIME
            : PartTimeStatusEnum.valueOf(dto.getPartTimeStatus()));
        employee.setStatus(EmployeeStatusEnum.ACTIVE);
        employee.setHireDate(dto.getHireDate());
        employee.setSocialInsuranceEnabled(Boolean.TRUE.equals(dto.getSocialInsuranceEnabled()));
        employee.setHousingFundAmount(dto.getHousingFundAmount());
        employee.setCommercialInsurance(Boolean.TRUE.equals(dto.getCommercialInsurance()));
        employee.setDormitoryEnabled(Boolean.TRUE.equals(dto.getDormitoryEnabled()));
        employee.setRemark(dto.getRemark());
        return employee;
    }

    /**
     * 将更新 DTO 应用到已有员工（仅更新非空字段）。
     *
     * @param employee 已有员工
     * @param dto      更新 DTO
     */
    public static void applyUpdate(Employee employee, EmployeeUpdateDTO dto) {
        if (dto.getName() != null) {
            employee.setName(dto.getName());
        }
        if (dto.getPhone() != null) {
            employee.setPhone(dto.getPhone());
        }
        if (dto.getDeptId() != null) {
            employee.setDeptId(dto.getDeptId());
        }
        if (dto.getPostId() != null) {
            employee.setPostId(dto.getPostId());
        }
        if (dto.getRole() != null) {
            employee.setRole(EmployeeRoleEnum.valueOf(dto.getRole()));
        }
        if (dto.getPartTimeStatus() != null) {
            employee.setPartTimeStatus(PartTimeStatusEnum.valueOf(dto.getPartTimeStatus()));
        }
        if (dto.getSocialInsuranceEnabled() != null) {
            employee.setSocialInsuranceEnabled(dto.getSocialInsuranceEnabled());
        }
        if (dto.getHousingFundAmount() != null) {
            employee.setHousingFundAmount(dto.getHousingFundAmount());
        }
        if (dto.getCommercialInsurance() != null) {
            employee.setCommercialInsurance(dto.getCommercialInsurance());
        }
        if (dto.getDormitoryEnabled() != null) {
            employee.setDormitoryEnabled(dto.getDormitoryEnabled());
        }
        if (dto.getRemark() != null) {
            employee.setRemark(dto.getRemark());
        }
    }

    /**
     * 领域对象 → 前端返回 DTO。
     *
     * @param employee 员工聚合根
     * @return 员工 DTO
     */
    public static EmployeeDTO toDTO(Employee employee) {
        EmployeeDTO dto = new EmployeeDTO();
        dto.setId(employee.getId());
        dto.setUserId(employee.getUserId());
        dto.setEmployeeCode(employee.getEmployeeCode());
        dto.setName(employee.getName());
        dto.setPhone(employee.getPhone());
        dto.setDeptId(employee.getDeptId());
        dto.setPostId(employee.getPostId());
        dto.setRole(employee.getRole() == null ? null : employee.getRole().name());
        dto.setPartTimeStatus(employee.getPartTimeStatus() == null ? null : employee.getPartTimeStatus().name());
        dto.setStatus(employee.getStatus() == null ? null : employee.getStatus().name());
        dto.setHireDate(employee.getHireDate());
        dto.setResignDate(employee.getResignDate());
        dto.setSocialInsuranceEnabled(employee.isSocialInsuranceEnabled());
        dto.setHousingFundAmount(employee.getHousingFundAmount());
        dto.setCommercialInsurance(employee.isCommercialInsurance());
        dto.setDormitoryEnabled(employee.isDormitoryEnabled());
        dto.setRemark(employee.getRemark());
        EmployeeLevel current = employee.getCurrentLevel();
        if (current != null) {
            dto.setCurrentLevelCode(current.getLevelCode());
        }
        return dto;
    }
}
