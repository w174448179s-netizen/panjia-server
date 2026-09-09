package com.panjia.people.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.panjia.people.domain.Employee;
import com.panjia.people.domain.EmployeeStatus;
import com.panjia.people.dto.AccountUserInfo;
import com.panjia.people.dto.ReconcileResult;
import com.panjia.people.mapper.EmployeeMapper;
import com.panjia.people.port.AccountPort;
import com.panjia.people.port.PostRolePort;
import com.panjia.people.service.ReconcileService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;

/**
 * 对账服务实现：people 单向覆盖 sys_user。
 */
@Service
@RequiredArgsConstructor
public class ReconcileServiceImpl implements ReconcileService {

    /** 差异字段：归属部门 */
    private static final String FIELD_DEPT = "dept";
    /** 差异字段：岗位/角色 */
    private static final String FIELD_POSTS = "posts";
    /** 差异字段：账户状态 */
    private static final String FIELD_STATUS = "status";

    private final EmployeeMapper employeeMapper;
    private final AccountPort accountPort;
    private final PostRolePort postRolePort;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ReconcileResult runReconcile() {
        ReconcileResult result = new ReconcileResult();
        List<Employee> employees = employeeMapper.selectList(new LambdaQueryWrapper<Employee>()
            .isNotNull(Employee::getUserId));
        result.setTotalEmployees(employees.size());

        for (Employee emp : employees) {
            AccountUserInfo account = accountPort.loadUser(emp.getUserId());
            if (account == null) {
                continue;
            }

            // ① 归属部门
            if (!Objects.equals(emp.getDeptId(), account.getDeptId())) {
                accountPort.updateDept(emp.getUserId(), emp.getDeptId());
                result.add(new ReconcileResult.DiffItem(
                    emp.getEmployeeId(), emp.getEmployeeCode(), emp.getEmployeeName(),
                    FIELD_DEPT, String.valueOf(account.getDeptId()), String.valueOf(emp.getDeptId())));
            }

            // ② 岗位集合（按当前绑定岗位名推导期望岗位 ID；查不到的岗位 resolvePost 兜底建）
            List<String> postNames = account.getPostNames() == null
                ? List.of()
                : account.getPostNames().stream().sorted().distinct().toList();
            List<Long> expectedPostIds = postNames.stream()
                .map(postRolePort::resolvePost)
                .sorted()
                .distinct()
                .toList();
            List<Long> actualPostIds = account.getPostIds() == null
                ? List.of()
                : account.getPostIds().stream().sorted().distinct().toList();
            if (!expectedPostIds.equals(actualPostIds)) {
                accountPort.rebuildPostsAndRoles(emp.getUserId(), postNames);
                result.add(new ReconcileResult.DiffItem(
                    emp.getEmployeeId(), emp.getEmployeeCode(), emp.getEmployeeName(),
                    FIELD_POSTS, actualPostIds.toString(), expectedPostIds.toString()));
            }

            // ③ 离职但账户仍启用 → 禁用
            if (emp.getStatus() == EmployeeStatus.LEFT && Boolean.TRUE.equals(account.getEnabled())) {
                accountPort.disableUser(emp.getUserId());
                result.add(new ReconcileResult.DiffItem(
                    emp.getEmployeeId(), emp.getEmployeeCode(), emp.getEmployeeName(),
                    FIELD_STATUS, "enabled", "disabled"));
            }
        }
        return result;
    }
}
