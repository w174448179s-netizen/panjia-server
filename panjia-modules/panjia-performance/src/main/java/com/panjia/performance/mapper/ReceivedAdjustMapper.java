package com.panjia.performance.mapper;

import com.baomidou.mybatisplus.core.toolkit.CollectionUtils;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;
import com.panjia.performance.domain.ReceivedAdjust;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 实收调整单 Mapper（pj_perf_received_adjust）。
 */
@Mapper
public interface ReceivedAdjustMapper extends BaseMapperPlus<ReceivedAdjust, ReceivedAdjust> {

    /**
     * 批量查询员工姓名（按员工 ID）。
     */
    @Select("<script>"
        + "SELECT employee_id AS \"employeeId\", employee_name AS \"employeeName\", "
        + "employee_code AS \"employeeCode\", dept_id AS \"deptId\" "
        + "FROM pj_people_employee "
        + "WHERE employee_id IN "
        + "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach>"
        + "</script>")
    List<Map<String, Object>> selectEmployeeNamesByIds(@Param("ids") List<Long> ids);

    /** 空集合安全：调用方直接传，无需判空 */
    default List<Map<String, Object>> employeeNames(List<Long> ids) {
        if (CollectionUtils.isEmpty(ids)) {
            return Collections.emptyList();
        }
        return selectEmployeeNamesByIds(ids);
    }

    /**
     * 批量查询部门名称（按部门 ID）。
     */
    @Select("<script>"
        + "SELECT dept_id AS \"deptId\", dept_name AS \"deptName\" "
        + "FROM sys_dept "
        + "WHERE dept_id IN "
        + "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach>"
        + "</script>")
    List<Map<String, Object>> selectDeptNamesByIds(@Param("ids") List<Long> ids);

    /** 空集合安全：按 ID 批量查部门名 */
    default List<Map<String, Object>> deptNames(List<Long> ids) {
        if (CollectionUtils.isEmpty(ids)) {
            return Collections.emptyList();
        }
        return selectDeptNamesByIds(ids);
    }

    /**
     * 按业务键集合批量查询审批中的实收调整单（SUBMITTED/APPROVED）。
     * <p>
     * 供实收合同列表「调整审批中」标记与目标金额预演，投影结构与
     * {@code PerformanceAdjustMapper.doSelectPendingByBizKeys} 对齐
     * （adjustType 固定 RECEIVED_AMOUNT，factType 语义 PERF_REAL）。
     *
     * @param period 归属期间
     * @param keys   业务键集合（合同号/订单号混合）
     * @return 每行含 bizKey/orderNo/id/adjustNo/adjustType/targetAmount/originalAmount/payloadJson
     */
    @Select("""
        <script>
        SELECT DISTINCT ON (contract_no, order_no)
               contract_no AS "bizKey",
               order_no AS "orderNo",
               id AS "id",
               adjust_no AS "adjustNo",
               'RECEIVED_AMOUNT' AS "adjustType",
               target_amount AS "targetAmount",
               original_amount AS "originalAmount",
               payload_json AS "payloadJson"
        FROM pj_perf_received_adjust
        WHERE status IN ('SUBMITTED', 'APPROVED')
          <if test="period != null and period != ''">AND period = #{period}</if>
          AND (contract_no IN
        <foreach collection="keys" item="k" open="(" separator="," close=")">#{k}</foreach>
            OR order_no IN
        <foreach collection="keys" item="k" open="(" separator="," close=")">#{k}</foreach>)
        ORDER BY contract_no, order_no, id DESC
        </script>
        """)
    List<Map<String, Object>> doSelectPendingByBizKeys(@Param("period") String period,
                                                       @Param("keys") java.util.Collection<String> keys);

    /**
     * 实收调整列表「业务类型」下拉：按数据范围（期间/部门子树/员工）取可见单据的去重业务类型。
     */
    @Select("""
        <script>
        SELECT DISTINCT biz_type
        FROM pj_perf_received_adjust
        WHERE biz_type IS NOT NULL AND biz_type &lt;&gt; ''
          <if test="period != null and period != ''">AND period = #{period}</if>
          <if test="employeeId != null">AND employee_id = #{employeeId}</if>
          <if test="deptId != null">
            AND (dept_id = #{deptId}
                 OR dept_id IN (SELECT sd.dept_id FROM sys_dept sd
                                WHERE sd.ancestors LIKE CONCAT('%', #{deptId}, '%')))
          </if>
        ORDER BY biz_type
        </script>
        """)
    List<String> selectDistinctBizTypes(@Param("period") String period,
                                        @Param("deptId") Long deptId,
                                        @Param("employeeId") Long employeeId);
}
