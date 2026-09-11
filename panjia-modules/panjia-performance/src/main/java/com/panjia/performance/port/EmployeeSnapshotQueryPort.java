package com.panjia.performance.port;

import com.panjia.performance.dto.EmployeeSnapshotDTO;

import java.util.List;

/**
 * 只读 people 域员工快照的端口接口。
 * <p>
 * 业绩域通过此端口读取员工基本信息与所属部门，用于业绩事实的员工归属校验与部门维度聚合。
 * 实现见 adapter 层 {@code PeopleSnapshotAdapter}。
 */
public interface EmployeeSnapshotQueryPort {

    /**
     * 按员工ID获取快照。
     *
     * @param employeeId 员工ID
     * @return 员工快照；不存在返回 null
     */
    EmployeeSnapshotDTO getByEmployeeId(Long employeeId);

    /**
     * 按工号获取快照。
     *
     * @param employeeCode 员工工号
     * @return 员工快照；不存在返回 null
     */
    EmployeeSnapshotDTO getByEmployeeCode(String employeeCode);

    /**
     * 按部门查询员工列表。
     *
     * @param deptId 部门ID
     * @return 该部门下的员工快照列表
     */
    List<EmployeeSnapshotDTO> listByDeptId(Long deptId);
}
