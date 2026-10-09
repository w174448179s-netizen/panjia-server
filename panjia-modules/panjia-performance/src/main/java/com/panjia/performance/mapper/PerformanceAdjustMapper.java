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
     * @return 每行含 employee_id / employee_name / employee_code / dept_id；ids 为空时返回空列表
     */
    @Select("<script>"
        + "SELECT employee_id AS \"employeeId\", employee_name AS \"employeeName\", "
        + "employee_code AS \"employeeCode\", dept_id AS \"deptId\" "
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
     * 批量查询已执行调整单的原始金额（按业务键聚合，取最早一条的 original_amount 快照）。
     *
     * @param period      归属期间
     * @param factType    事实口径
     * @param contractNos 业务键集合（合同号/订单号混合，调整单 contract_no 存的是提交时的展示键）
     * @return 每行含 bizKey(contract_no) / originalAmount；空集合时返回空列表
     */
    @Select("""
        <script>
        SELECT DISTINCT ON (contract_no)
               contract_no AS "bizKey",
               original_amount AS "originalAmount"
        FROM pj_perf_adjust
        WHERE status = 'EXECUTED'
          <if test="period != null and period != ''">AND period = #{period}</if>
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

    /**
     * 批量查询审批中的合同级调整单（SUBMITTED/APPROVED，尚未执行，每合同取最新一单）。
     * <p>
     * 用于列表/详情展示「调整审批中」标记 + 目标金额：执行前事实金额未变，
     * 仅靠 originalAmount（只取 EXECUTED）无法感知在途调整。
     *
     * @param period    归属期间
     * @param factType  事实口径
     * @param keys      业务键集合（合同号/订单号混合，调整单 contract_no 存的是提交时的展示键）
     * @return 每行含 bizKey / adjustType / targetAmount；空集合返回空列表
     */
    @Select("""
        <script>
        SELECT DISTINCT ON (contract_no)
               contract_no AS "bizKey",
               id AS "id",
               adjust_no AS "adjustNo",
               adjust_type AS "adjustType",
               target_amount AS "targetAmount",
               original_amount AS "originalAmount",
               payload_json AS "payloadJson"
        FROM pj_perf_adjust
        WHERE 1 = 1
          <if test="period != null and period != ''">AND period = #{period}</if>
          AND fact_type = #{factType}
          AND status IN ('SUBMITTED', 'APPROVED')
          AND contract_no IN
        <foreach collection="keys" item="k" open="(" separator="," close=")">#{k}</foreach>
        ORDER BY contract_no, id DESC
        </script>
        """)
    List<Map<String, Object>> doSelectPendingByBizKeys(@Param("period") String period,
                                                       @Param("factType") String factType,
                                                       @Param("keys") java.util.Collection<String> keys);

    /**
     * 批量查询审批中的明细级调整单（SUBMITTED/APPROVED，每事实取最新一单）。
     *
     * @param factIds 事实 ID 集合
     * @return 每行含 factId / adjustType / targetAmount；空集合返回空列表
     */
    @Select("""
        <script>
        SELECT DISTINCT ON (fact_id)
               fact_id AS "factId",
               adjust_type AS "adjustType",
               target_amount AS "targetAmount"
        FROM pj_perf_adjust
        WHERE status IN ('SUBMITTED', 'APPROVED')
          AND fact_id IN
        <foreach collection="factIds" item="id" open="(" separator="," close=")">#{id}</foreach>
        ORDER BY fact_id, id DESC
        </script>
        """)
    List<Map<String, Object>> doSelectPendingByFactIds(@Param("factIds") java.util.Collection<Long> factIds);

    /**
     * 批量查询已执行的合同级调整单（含链式多次调整，新→旧排列，供逆向还原每人调整前金额）。
     * <p>
     * 覆盖两类合同级调整：
     * <ul>
     *   <li>AMOUNT：执行时按占比分摊到各事实但不落 fact_id 级痕迹，分摊具有等比不变性，
     *       可用当前金额 + 逆向分摊精确还原调整前金额；</li>
     *   <li>ADD_MEMBER：合同总额不变，逐人扣减/新人 0→X 记录在 payload_json，
     *       服务层按快照逐行反转。</li>
     * </ul>
     *
     * @param period    归属期间
     * @param factType  事实口径
     * @param keys      业务键集合（合同号/订单号混合）
     * @return 每行含 id / adjustNo / bizKey / adjustType / targetAmount / originalAmount / payloadJson，按 id 倒序
     */
    @Select("""
        <script>
        SELECT id AS "id",
               adjust_no AS "adjustNo",
               contract_no AS "bizKey",
               adjust_type AS "adjustType",
               target_amount AS "targetAmount",
               original_amount AS "originalAmount",
               payload_json AS "payloadJson"
        FROM pj_perf_adjust
        WHERE period = #{period}
          AND fact_type = #{factType}
          AND status = 'EXECUTED'
          AND adjust_scope = 'CONTRACT'
          AND adjust_type IN ('AMOUNT', 'ADD_MEMBER')
          AND contract_no IN
        <foreach collection="keys" item="k" open="(" separator="," close=")">#{k}</foreach>
        ORDER BY id DESC
        </script>
        """)
    List<Map<String, Object>> doSelectExecutedContractAdjusts(@Param("period") String period,
                                                              @Param("factType") String factType,
                                                              @Param("keys") java.util.Collection<String> keys);
}
