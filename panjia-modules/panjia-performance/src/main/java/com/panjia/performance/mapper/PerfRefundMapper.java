package com.panjia.performance.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.panjia.performance.domain.PerfRefund;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 退单申请单 Mapper（pj_perf_refund）。
 */
@Mapper
public interface PerfRefundMapper extends BaseMapper<PerfRefund> {

    /**
     * 批量查合同最近一次「已执行」退单的落账月（合同列表标记「已退单」用）。
     *
     * @param contractNos 合同号集合（空集合不发起 SQL）
     * @return contractNo → 最近退单落账月 YYYY-MM
     */
    @Select("""
        <script>
        SELECT contract_no AS "contractNo", MAX(refund_period) AS "refundPeriod"
        FROM pj_perf_refund
        WHERE status = 'EXECUTED'
          AND contract_no IN
          <foreach collection="contractNos" item="cn" open="(" separator="," close=")">#{cn}</foreach>
        GROUP BY contract_no
        </script>
        """)
    List<Map<String, Object>> selectRefundedPeriods(@Param("contractNos") List<String> contractNos);

    /**
     * 统计指定期间的退单冲减合计（退单列表页顶部汇总用）。
     *
     * @param period 退单落账期间 YYYY-MM（可空=不限）
     * @return 冲减新签合计 / 冲减实收合计 / 退单笔数
     */
    @Select("""
        <script>
        SELECT COALESCE(SUM(expect_amount), 0)     AS "expectAmount",
               COALESCE(SUM(real_amount), 0)       AS "realAmount",
               COALESCE(SUM(commission_amount), 0) AS "commissionAmount",
               COUNT(1)                            AS "refundCount"
        FROM pj_perf_refund
        WHERE status = 'EXECUTED'
          <if test="period != null and period != ''">AND refund_period = #{period}</if>
        </script>
        """)
    Map<String, Object> sumExecutedAmounts(@Param("period") String period);

    /**
     * 批量回填部门名称。
     */
    @Select("""
        <script>
        SELECT dept_id AS "deptId", dept_name AS "deptName"
        FROM sys_dept
        WHERE dept_id IN
        <foreach collection="deptIds" item="id" open="(" separator="," close=")">#{id}</foreach>
        </script>
        """)
    List<Map<String, Object>> deptNames(@Param("deptIds") List<Long> deptIds);

    /**
     * 某合同是否已有生效中的退单（SUBMITTED/APPROVED/EXECUTED）——重复退单拦截。
     */
    @Select("""
        SELECT COUNT(1)
        FROM pj_perf_refund
        WHERE contract_no = #{contractNo}
          AND status IN ('SUBMITTED', 'APPROVED', 'EXECUTED')
        """)
    long countEffectiveByContract(@Param("contractNo") String contractNo);

    /**
     * 某退单单据的新签正事实合计（执行前快照校验用，负号已加）。
     */
    @Select("""
        SELECT COALESCE(ABS(SUM(performance_amount)), 0)
        FROM pj_perf_fact
        WHERE contract_no = #{contractNo}
          AND fact_type = 'PERF_EXPECT'
          AND fact_status = 'ACTIVE'
          AND reversal_type IS NULL
          AND performance_amount > 0
          <if test="orderNo != null and orderNo != ''">AND order_no = #{orderNo}</if>
        """)
    BigDecimal sumRefundableExpect(@Param("contractNo") String contractNo, @Param("orderNo") String orderNo);
}
