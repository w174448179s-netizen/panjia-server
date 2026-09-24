package com.panjia.contracts.port;

import com.panjia.contracts.snapshot.EmployeeSnapshot;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
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
     * 按员工姓名批量查员工引用（ID + 工号）。
     * <p>
     * 历史工资导入归一化用：天街历史表无工号列，只能按姓名匹配员工。
     * 重名（同名命中多行）视为歧义不返回——调用方记 EMPLOYEE_NOT_MATCH issue，
     * 与老导入器「唯一匹配才取，重名跳过」语义一致。含离职员工（历史工资补录）。
     *
     * @param names 姓名集合
     * @return 姓名 → 员工引用（仅含唯一匹配项）
     */
    Map<String, com.panjia.contracts.dto.EmployeeRef> findEmployeeRefsByNames(Collection<String> names);

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

    /**
     * 取单个员工指定月份的<b>强类型</b>算薪事实快照（§10.6 {@link EmployeeSnapshot}）。
     * <p>
     * 与 {@link #getSnapshotAt(Long, LocalDate)} 的 String Map 同源，身份字段取自员工主数据
     * 当前行，事实字段取该月月末所在闭开区间切片；字段级契约供 payroll / commission 直接编码。
     *
     * @param employeeId   员工 ID
     * @param pointInMonth 算薪月份内任意一天（取该月月末切片）
     * @return 员工快照；员工不存在时返回 null
     */
    EmployeeSnapshot getEmployeeSnapshot(Long employeeId, LocalDate pointInMonth);

    /**
     * 批量取多个员工同一月份的强类型算薪事实快照（算薪批次用，避免逐人查库）。
     *
     * @param employeeIds  员工 ID 集合
     * @param pointInMonth 算薪月份内任意一天
     * @return employeeId → 员工快照（入参存在但员工已删除的 ID 不在结果中）
     */
    Map<Long, EmployeeSnapshot> getEmployeeSnapshots(Collection<Long> employeeIds, LocalDate pointInMonth);

    /**
     * 按职级查指定月份在职（ACTIVE/PARTTIME）员工的 ID 集合。
     * <p>算薪名单扩展用：职级规则含底薪/保底的员工（店长、带底薪职级）
     * 即使当月无结佣/手工项也应进入算薪名单。
     *
     * @param levelCodes   职级编码集合
     * @param pointInMonth 算薪月份内任意一天
     * @return 持有这些职级事实且未离职的员工 ID
     */
    Collection<Long> findEmployeeIdsByLevels(Collection<String> levelCodes, LocalDate pointInMonth);

    /**
     * 批量取多个部门及其所有下级部门 ID（含自身，递归到叶子）。
     * <p>算薪时总监提成按管辖门店分别跳点：总监 deptId 下所有子孙门店的新签/社保
     * 各自独立计薪，汇总提成金额。
     *
     * @param deptIds 根部门 ID 集合
     * @return deptId → 含自身及所有子孙的部门 ID 列表；入参为空返回空 Map
     */
    Map<Long, List<Long>> findDeptAndChildren(Collection<Long> deptIds);

    /**
     * 批量取多个部门的直接子部门 ID（仅下一层，不含自身）。
     * <p>总监挂在大区，提成按门店级分行：取大区下直接子部门（门店），
     * 门店下组别级数据由调用方向上汇总，避免一个门店出现多条提成行。
     *
     * @param deptIds 根部门 ID 集合
     * @return deptId → 直接子部门 ID 列表；入参为空返回空 Map
     */
    Map<Long, List<Long>> findDirectChildren(Collection<Long> deptIds);

    /**
     * 批量取部门展示名（单层 dept_name，如"云庭店"）。
     * <p>总监多门店提成明细导出时按门店分行展示门店名。
     *
     * @param deptIds 部门 ID 集合
     * @return deptId → 部门名；入参为空返回空 Map
     */
    Map<Long, String> findDeptNames(Collection<Long> deptIds);
}
