package com.panjia.performance.mapper;

import com.baomidou.mybatisplus.core.toolkit.CollectionUtils;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;
import com.panjia.performance.domain.PerformanceAdjust;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 业绩调整单 Mapper。
 */
@Mapper
public interface PerformanceAdjustMapper extends BaseMapperPlus<PerformanceAdjust, PerformanceAdjust> {

    /**
     * 批量查询员工姓名（按员工 ID）。
     *
     * @param ids 员工 ID 集合
     * @return 每行含 employee_id / employee_name；ids 为空时返回空列表
     */
    @Select("<script>"
        + "SELECT employee_id AS \"employeeId\", employee_name AS \"employeeName\" "
        + "FROM pj_people_employee "
        + "WHERE employee_id IN "
        + "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach>"
        + "</script>")
    List<Map<String, Object>> selectEmployeeNamesByIds(@Param("ids") List<Long> ids);

    /**
     * 批量查询部门名称（按部门 ID）。
     *
     * @param ids 部门 ID 集合
     * @return 每行含 dept_id / dept_name；ids 为空时返回空列表
     */
    @Select("<script>"
        + "SELECT dept_id AS \"deptId\", dept_name AS \"deptName\" "
        + "FROM sys_dept "
        + "WHERE dept_id IN "
        + "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach>"
        + "</script>")
    List<Map<String, Object>> selectDeptNamesByIds(@Param("ids") List<Long> ids);

    /** 空集合安全：调用方直接传，无需判空 */
    default List<Map<String, Object>> employeeNames(List<Long> ids) {
        if (CollectionUtils.isEmpty(ids)) {
            return Collections.emptyList();
        }
        return selectEmployeeNamesByIds(ids);
    }

    default List<Map<String, Object>> deptNames(List<Long> ids) {
        if (CollectionUtils.isEmpty(ids)) {
            return Collections.emptyList();
        }
        return selectDeptNamesByIds(ids);
    }

    /**
     * 批量查询业绩事实金额（按事实 ID，performance_amount 口径）。
     *
     * @param ids 事实 ID 集合
     * @return 每行含 factId / amount；ids 为空时返回空列表
     */
    @Select("<script>"
        + "SELECT id AS \"factId\", performance_amount AS \"amount\" "
        + "FROM pj_perf_fact "
        + "WHERE id IN "
        + "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach>"
        + "</script>")
    List<Map<String, Object>> selectFactAmountsByIds(@Param("ids") List<Long> ids);

    /**
     * 合同级调整：汇总该合同下全部 ACTIVE 事实的金额（performance_amount 口径）。
     *
     * @param period     归属期间（跨月调整传原业绩归属月）
     * @param factType   事实口径
     * @param contractNo 合同号
     * @return 金额合计，无匹配事实时为 0
     */
    @Select("""
        SELECT COALESCE(SUM(f.performance_amount), 0)
        FROM pj_perf_fact f
        WHERE f.fact_status = 'ACTIVE'
          AND f.period = #{period}
          AND f.fact_type = #{factType}
          AND (f.contract_no = #{contractNo} OR f.order_no = #{contractNo})
        """)
    java.math.BigDecimal selectContractTotalAmount(@Param("period") String period,
                                                    @Param("factType") String factType,
                                                    @Param("contractNo") String contractNo);

    /** 空集合安全 */
    default List<Map<String, Object>> selectFactAmountsByIdsSafe(List<Long> ids) {
        if (CollectionUtils.isEmpty(ids)) {
            return Collections.emptyList();
        }
        return selectFactAmountsByIds(ids);
    }



    /**
     * 批量查询已执行调整单的原始金额（按合同号聚合，取最早一条的 original_amount 快照）。
     *
     * @param period      归属期间
     * @param factType    事实口径
     * @param contractNos 合同号集合
     * @return 每行含 bizKey(contract_no) / originalAmount；空集合时返回空列表
     */
    @Select("""
        <script>
        SELECT DISTINCT ON (contract_no)
               contract_no AS "bizKey",
               original_amount AS "originalAmount"
        FROM pj_perf_adjust
        WHERE period = #{period}
          AND status = 'EXECUTED'
          AND contract_no IN
        <foreach collection="contractNos" item="k" open="(" separator="," close=")">#{k}</foreach>
        ORDER BY contract_no, id ASC
        </script>
        """)
    List<Map<String, Object>> doSelectOriginalAmounts(@Param("period") String period,
                                                       @Param("factType") String factType,
                                                       @Param("contractNos") java.util.Collection<String> contractNos);

    /**
     * 批量查事实的调整前金额（按 factId 取最早一条 EXECUTED 调整单的 original_amount 快照）。
     *
     * @param factIds 事实 ID 集合
     * @return 每行含 factId / originalAmount；空集合返回空列表
     */
    default List<Map<String, Object>> selectOriginalAmountsByFactIds(java.util.Collection<Long> factIds) {
        if (CollectionUtils.isEmpty(factIds)) {
            return Collections.emptyList();
        }
        return doSelectOriginalAmountsByFactIds(factIds);
    }

    @Select("""
        <script>
        SELECT DISTINCT ON (fact_id)
               fact_id AS "factId",
               original_amount AS "originalAmount"
        FROM pj_perf_adjust
        WHERE status = 'EXECUTED'
          AND fact_id IN
        <foreach collection="factIds" item="id" open="(" separator="," close=")">#{id}</foreach>
        ORDER BY fact_id, id ASC
        </script>
        """)
    List<Map<String, Object>> doSelectOriginalAmountsByFactIds(@Param("factIds") java.util.Collection<Long> factIds);
}
