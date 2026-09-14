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
 * 员工姓名翻译实现：员工档案 ID（pj_people_employee.employee_id）→ 员工姓名。
 * <p>
 * 业务角色（店长/财务/人事/经纪人）没有 system:user:query 权限，前端无法自行
 * 查用户表翻译；结佣/算薪等明细行的 employee_id 又是员工档案表 ID 而非 sys_user ID，
 * RuoYi 自带的 user_id_to_nickname 不适用，故由后端统一翻译。
 * <p>
 * 放在 people 域（员工主数据的唯一入口），通过 Spring Bean 被 ruoyi-common-translation
 * 的 TranslationConfig 自动收集；其他业务域实体只引用 panjia-common 里的
 * {@link PanjiaTransConstant#EMPLOYEE_ID_TO_NAME} 字符串常量，不产生模块间依赖。
 * <p>
 * 源 ID 为 null 时输出 null（前端自行兜底），不抛异常。
 */
@Component
@AllArgsConstructor
@TranslationType(type = PanjiaTransConstant.EMPLOYEE_ID_TO_NAME)
public class EmployeeNameTranslationImpl implements TranslationInterface<String> {

    private final EmployeeMapper employeeMapper;

    /**
     * 单值翻译：员工 ID → 姓名。
     *
     * @param key   员工 ID（Long；其他类型返回 null）
     * @param other 额外参数（未用）
     * @return 员工姓名；员工不存在返回 null
     */
    @Override
    public String translation(Object key, String other) {
        if (key instanceof Long id) {
            Employee employee = employeeMapper.selectById(id);
            return employee == null ? null : employee.getEmployeeName();
        }
        return null;
    }

    /**
     * 批量翻译：一次 IN 查询取回全部姓名，避免明细行逐条查库。
     *
     * @param keys  员工 ID 集合
     * @param other 额外参数（未用）
     * @return 原始键与员工姓名的映射
     */
    @Override
    public Map<Object, String> translationBatch(Set<Object> keys, String other) {
        Set<Long> ids = collectLongIds(keys);
        if (ids.isEmpty()) {
            return Map.of();
        }
        List<Employee> employees = employeeMapper.selectList(
            Wrappers.<Employee>lambdaQuery().in(Employee::getEmployeeId, ids));
        Map<Long, String> nameMap = new LinkedHashMap<>(employees.size());
        for (Employee employee : employees) {
            nameMap.put(employee.getEmployeeId(), employee.getEmployeeName());
        }
        Map<Object, String> result = new LinkedHashMap<>(keys.size());
        for (Object key : keys) {
            result.put(key, key == null ? null : nameMap.get(Convert.toLong(key)));
        }
        return result;
    }
}
