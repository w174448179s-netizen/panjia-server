package com.panjia.contracts.port;

import com.panjia.contracts.snapshot.EmployeeSnapshot;

import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * 员工查询 Port —— 下游域通过此接口获取员工快照（跨域唯一交互方式）。
 * <p>
 * 实现方：panjia-people（EmployeeSnapshotService）。
 * 消费方：panjia-commission（结佣时点）、panjia-payroll（算薪月末时点）。
 * <p>
 * 铁律：下游域只依赖此接口，禁止依赖 people 域聚合根 Employee。
 */
public interface EmployeeQueryPort {

    /**
     * 为单个员工在指定时点创建快照。
     *
     * @param employeeId  员工 ID（雪花 ID）
     * @param pointInTime 快照时点（算薪月末 / 结佣确认日）
     * @return 员工快照（只读）
     */
    EmployeeSnapshot takeSnapshot(Long employeeId, Date pointInTime);

    /**
     * 批量创建快照。
     *
     * @param employeeIds 员工 ID 列表
     * @param pointInTime 统一快照时点
     * @return Map：employeeId → 快照
     */
    Map<Long, EmployeeSnapshot> takeSnapshots(List<Long> employeeIds, Date pointInTime);

    /**
     * 查询指定部门（门店）在某时点所有在职员工的快照。
     *
     * @param deptId      部门（门店）ID
     * @param pointInTime 快照时点
     * @return 快照列表
     */
    List<EmployeeSnapshot> takeSnapshotsByDept(Long deptId, Date pointInTime);
}
