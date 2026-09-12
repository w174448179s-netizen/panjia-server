package com.panjia.contracts.port;

import com.panjia.contracts.dto.EmployeeMainDataDTO;

import java.util.Collection;
import java.util.Map;

/**
 * 员工主数据跨域查询端口（people 域对外契约）。
 * <p>
 * 由 panjia-people 提供实现（EmployeeMapper + DeptPort），
 * performance / payroll 等消费域通过本端口读取员工姓名/部门，禁止直接摸 pj_employee 表。
 */
public interface EmployeeMainDataQueryPort {

    /**
     * 按工号查员工主数据。
     *
     * @param employeeCode 工号
     * @return 员工主数据；不存在返回 null
     */
    EmployeeMainDataDTO getByEmployeeCode(String employeeCode);

    /**
     * 按员工ID查员工主数据。
     *
     * @param employeeId 员工ID
     * @return 员工主数据；不存在返回 null
     */
    EmployeeMainDataDTO getByEmployeeId(Long employeeId);

    /**
     * 按系统用户ID查员工主数据（用于经纪人等角色数据权限：登录用户 → 员工）。
     *
     * @param userId 系统用户ID（sys_user.user_id）
     * @return 员工主数据；不存在返回 null
     */
    EmployeeMainDataDTO getByUserId(Long userId);

    /**
     * 按工号集合批量查员工主数据（列表页关联展示用，避免 N+1）。
     *
     * @param employeeCodes 工号集合
     * @return 工号 → 员工主数据；未匹配的工号不在结果中
     */
    Map<String, EmployeeMainDataDTO> listByCodes(Collection<String> employeeCodes);
}
