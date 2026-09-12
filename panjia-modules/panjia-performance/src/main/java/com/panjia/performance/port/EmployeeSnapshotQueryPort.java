package com.panjia.performance.port;

import com.panjia.contracts.snapshot.EmployeeSnapshot;

import java.util.List;

/**
 * 只读 people 域员工快照的端口接口（业绩域侧端口，contracts 快照类型的域内出口）。
 * <p>
 * 业绩域通过此端口读取员工身份与所属部门，用于业绩事实的员工归属填充与部门维度聚合。
 * 实现见 adapter 层 {@code PeopleSnapshotAdapter}。
 * <p>
 * <b>语义边界</b>：本端口返回的是员工<b>当前态身份视图</b>（contracts {@link EmployeeSnapshot}
 * 仅填充身份字段、snapshotDate=当天，职级/参保开关等时点事实为 null）。业绩事实记录的是
 * 金额，不需要历史人事切片；若未来需要月末完整事实切片，走 contracts
 * {@code PeopleQueryPort.getEmployeeSnapshot(id, pointInMonth)}。
 */
public interface EmployeeSnapshotQueryPort {

    /**
     * 按员工 ID 获取当前态身份快照。
     *
     * @param employeeId 员工 ID
     * @return 员工快照；不存在返回 null
     */
    EmployeeSnapshot getByEmployeeId(Long employeeId);

    /**
     * 按工号获取当前态身份快照。
     *
     * @param employeeCode 员工工号
     * @return 员工快照；不存在返回 null
     */
    EmployeeSnapshot getByEmployeeCode(String employeeCode);

    /**
     * 按部门查询员工列表。
     *
     * @param deptId 部门 ID
     * @return 该部门下的员工快照列表
     */
    List<EmployeeSnapshot> listByDeptId(Long deptId);
}
