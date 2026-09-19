package com.panjia.people.translation;

import cn.hutool.core.convert.Convert;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.panjia.common.constant.PanjiaTransConstant;
import com.panjia.people.domain.Employee;
import com.panjia.people.mapper.EmployeeMapper;
import lombok.AllArgsConstructor;
import org.dromara.common.translation.annotation.TranslationType;
import org.dromara.common.translation.core.TranslationInterface;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 员工工号翻译实现：员工档案 ID（pj_people_employee.employee_id）→ 工号（employeeCode）。
 * <p>
 * 与 {@link EmployeeNameTranslationImpl} 同属员工主数据翻译；工资/结佣明细行需同时展示
 * 姓名与工号，前端不再单独拉员工全量表（受分页限制 + 雪花 ID 精度影响易错位）。
 */
@Component
@AllArgsConstructor
@TranslationType(type = PanjiaTransConstant.EMPLOYEE_ID_TO_CODE)
public class EmployeeCodeTranslationImpl implements TranslationInterface<String> {

    private final EmployeeMapper employeeMapper;

    @Override
    public String translation(Object key, String other) {
        if (key instanceof Long id) {
            Employee employee = employeeMapper.selectById(id);
            return employee == null ? null : employee.getEmployeeCode();
        }
        return null;
    }

    @Override
    public Map<Object, String> translationBatch(Set<Object> keys, String other) {
        Set<Long> ids = collectLongIds(keys);
        if (ids.isEmpty()) {
            return Map.of();
        }
        List<Employee> employees = employeeMapper.selectList(
            Wrappers.<Employee>lambdaQuery().in(Employee::getEmployeeId, ids));
        Map<Long, String> codeMap = new LinkedHashMap<>(employees.size());
        for (Employee employee : employees) {
            codeMap.put(employee.getEmployeeId(), employee.getEmployeeCode());
        }
        Map<Object, String> result = new LinkedHashMap<>(keys.size());
        for (Object key : keys) {
            result.put(key, key == null ? null : codeMap.get(Convert.toLong(key)));
        }
        return result;
    }
}
