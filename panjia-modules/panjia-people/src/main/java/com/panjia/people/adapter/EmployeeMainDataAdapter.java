package com.panjia.people.adapter;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.panjia.contracts.dto.EmployeeMainDataDTO;
import com.panjia.contracts.port.EmployeeMainDataQueryPort;
import com.panjia.people.domain.Employee;
import com.panjia.people.mapper.EmployeeMapper;
import com.panjia.people.port.DeptPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.utils.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 员工主数据跨域端口实现（contracts {@link EmployeeMainDataQueryPort}）。
 * <p>
 * 用 EmployeeMapper 查 pj_employee 主数据 + DeptPort 批量取部门全路径名，
 * 供 performance / payroll 等消费域关联员工姓名与部门展示。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmployeeMainDataAdapter implements EmployeeMainDataQueryPort {

    private final EmployeeMapper employeeMapper;
    private final DeptPort deptPort;

    @Override
    public EmployeeMainDataDTO getByEmployeeCode(String employeeCode) {
        if (StringUtils.isBlank(employeeCode)) {
            return null;
        }
        Employee emp = employeeMapper.selectOne(new LambdaQueryWrapper<Employee>()
            .eq(Employee::getEmployeeCode, employeeCode).last("LIMIT 1"));
        return emp == null ? null : toDTO(emp);
    }

    @Override
    public EmployeeMainDataDTO getByEmployeeId(Long employeeId) {
        if (employeeId == null) {
            return null;
        }
        Employee emp = employeeMapper.selectById(employeeId);
        return emp == null ? null : toDTO(emp);
    }

    @Override
    public Map<String, EmployeeMainDataDTO> listByCodes(Collection<String> employeeCodes) {
        if (CollectionUtils.isEmpty(employeeCodes)) {
            return Map.of();
        }
        List<Employee> list = employeeMapper.selectList(new LambdaQueryWrapper<Employee>()
            .in(Employee::getEmployeeCode, employeeCodes));
        if (list.isEmpty()) {
            return Map.of();
        }
        // 一次批量取部门全路径名，避免逐行查部门
        Set<Long> deptIds = list.stream()
            .map(Employee::getDeptId)
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());
        Map<Long, String> deptNames = deptIds.isEmpty() ? Map.of() : deptPort.findDeptFullNames(deptIds);

        Map<String, EmployeeMainDataDTO> result = new LinkedHashMap<>(list.size());
        for (Employee emp : list) {
            EmployeeMainDataDTO dto = toDTO(emp);
            if (emp.getDeptId() != null) {
                dto.setDeptName(deptNames.get(emp.getDeptId()));
            }
            result.put(emp.getEmployeeCode(), dto);
        }
        return result;
    }

    private EmployeeMainDataDTO toDTO(Employee emp) {
        EmployeeMainDataDTO dto = new EmployeeMainDataDTO();
        dto.setEmployeeId(emp.getEmployeeId());
        dto.setEmployeeCode(emp.getEmployeeCode());
        dto.setEmployeeName(emp.getEmployeeName());
        dto.setDeptId(emp.getDeptId());
        dto.setStatus(emp.getStatus() == null ? null : emp.getStatus().getCode());
        dto.setUserId(emp.getUserId());
        return dto;
    }
}
