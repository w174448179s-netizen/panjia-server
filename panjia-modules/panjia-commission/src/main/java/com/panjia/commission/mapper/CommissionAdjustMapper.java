package com.panjia.commission.mapper;

import com.panjia.commission.domain.CommissionAdjust;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 结佣调整单 Mapper。
 */
@Mapper
public interface CommissionAdjustMapper extends BaseMapperPlus<CommissionAdjust, CommissionAdjust> {

    /** 空集合安全：IN 查询前统一判空 */
    default List<Long> selectSubDeptIds(Long deptId) {
        if (deptId == null) {
            return Collections.emptyList();
        }
        return selectSubDeptIdsRaw(deptId);
    }

    /**
     * 查询本部门及全部下级部门 ID（ancestors 祖先链包含本部门）。
     */
    @Select("""
        SELECT dept_id FROM sys_dept
        WHERE dept_id = #{deptId} OR ancestors LIKE CONCAT('%', #{deptId}, '%')
        """)
    List<Long> selectSubDeptIdsRaw(@Param("deptId") Long deptId);

    /**
     * 批量查询员工姓名/工号（按员工 ID）。
     *
     * @param ids 员工 ID 集合
     * @return 每行含 employeeId / employeeName / employeeCode；空集合返回空列表
     */
    @Select("""
        <script>
        SELECT employee_id AS "employeeId", employee_name AS "employeeName",
               employee_code AS "employeeCode"
        FROM pj_people_employee
        WHERE employee_id IN
        <foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach>
        </script>
        """)
    List<Map<String, Object>> selectEmployeeNamesByIds(@Param("ids") Collection<Long> ids);

    default List<Map<String, Object>> employeeNames(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return Collections.emptyList();
        }
        return selectEmployeeNamesByIds(ids);
    }

    /**
     * 批量查询部门名称（按部门 ID）。
     *
     * @param ids 部门 ID 集合
     * @return 每行含 deptId / deptName；空集合返回空列表
     */
    @Select("""
        <script>
        SELECT dept_id AS "deptId", dept_name AS "deptName"
        FROM sys_dept
        WHERE dept_id IN
        <foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach>
        </script>
        """)
    List<Map<String, Object>> selectDeptNamesByIds(@Param("ids") Collection<Long> ids);

    default List<Map<String, Object>> deptNames(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return Collections.emptyList();
        }
        return selectDeptNamesByIds(ids);
    }

    /**
     * 批量汇总各结佣申请单「已执行金额调整(AMOUNT)」的累计差额。
     * <p>
     * 结佣明细列表「结佣业绩：原值 → 调整后值」的原额 = 当前合计 − 累计差额
     * （连续多次调整时各次差额相对执行时的当时值，链式可加）。
     * 仅 EXECUTED 的 AMOUNT 单计入；ADD_MEMBER 不改变合同总额（新人金额由他人扣减分摊），不计入。
     *
     * @param applicationIds 申请单 ID 集合
     * @return 每行 applicationId / deltaSum
     */
    @Select("""
        <script>
        SELECT application_id AS "applicationId",
               COALESCE(SUM(diff_amount), 0) AS "deltaSum"
        FROM pj_commission_adjust
        WHERE status = 'EXECUTED'
          AND adjust_type = 'AMOUNT'
          AND application_id IN
        <foreach collection="applicationIds" item="aid" open="(" separator="," close=")">#{aid}</foreach>
        GROUP BY application_id
        </script>
        """)
    List<Map<String, Object>> selectExecutedAmountDeltaSum(@Param("applicationIds") Collection<Long> applicationIds);
}
