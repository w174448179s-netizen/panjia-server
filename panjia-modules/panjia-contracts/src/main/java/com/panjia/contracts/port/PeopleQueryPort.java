package com.panjia.contracts.port;

import java.time.LocalDate;
import java.util.Collection;
import java.util.Map;

/**
 * 员工域查询端口（people 域实现，payroll 等下游域消费）。
 * <p>
 * 按算薪月份取员工算薪事实快照：salary_fact 闭开区间
 * {@code [effective_date, expire_date)} 在该月的最新切片。
 * <p>
 * 返回 Map 的 key 为 {@link com.panjia.contracts.constant.PeopleFactFields}
 * 定义的 8 类 fact_type，value 一律为字符串：
 * 布尔类事实为 {@code "true"}/{@code "false"}，MENTOR 为师傅员工 ID 字符串，
 * 查无记录时对应 key 的 value 为 {@code null}。
 */
public interface PeopleQueryPort {

    /**
     * 按员工工号（外部编码）批量查员工 ID。
     * <p>
     * 导入域归一化时用于 employee_external_code → employee.id 匹配。
     *
     * @param codes 员工工号集合
     * @return employeeCode → employeeId
     */
    Map<String, Long> findEmployeeIdsByCodes(Collection<String> codes);

    /**
     * 取单个员工指定月份的算薪事实快照。
     *
     * @param employeeId   员工 ID
     * @param pointInMonth 算薪月份内任意一天（取该月 1 号所在区间，调用方传月份即可）
     * @return fact_type → value 的快照 Map（8 类 key 齐全，无记录的 value 为 null）
     */
    Map<String, String> getSnapshotAt(Long employeeId, LocalDate pointInMonth);

    /**
     * 批量取多个员工同一月份的算薪事实快照（算薪批次用，避免逐人查库）。
     *
     * @param employeeIds  员工 ID 集合
     * @param pointInMonth 算薪月份内任意一天
     * @return employeeId → 快照 Map
     */
    Map<Long, Map<String, String>> getSnapshotsAt(Collection<Long> employeeIds, LocalDate pointInMonth);
}
