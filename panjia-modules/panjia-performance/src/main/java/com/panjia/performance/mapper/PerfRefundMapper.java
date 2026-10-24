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
     * 业务键（合同号/订单号）是否已有生效中的手工退单（SUBMITTED/APPROVED/EXECUTED）。
     * <p>
     * 用于导入侧互斥（2026-10-25 定稿）：手工退单已镜像生成负新签/负实收/负结佣，
     * 此时再导入贝壳的退单负数行会重复冲减；命中即跳过该批次的负数行。
     * 含 SUBMITTED（审批中）：避免审批通过后才执行、导入已写入造成重复。
     *
     * @param contractNo 合同号（可空）
     * @param orderNo    订单号（可空）
     * @return 生效中退单条数；0 = 无手工退单
     */
    @Select("""
        <script>
        SELECT COUNT(1)
        FROM pj_perf_refund
        WHERE status IN ('SUBMITTED', 'APPROVED', 'EXECUTED')
          <!-- 排除贝壳导入自动登记的退单记录：其本身由导入负数生成，不能反过来拦截后续批次的部分退单行 -->
          AND (payload_json IS NULL OR payload_json NOT LIKE '%"source":"IMPORT"%')
          AND (
            <choose>
              <when test="contractNo != null and contractNo != ''">
                contract_no = #{contractNo}
                <if test="orderNo != null and orderNo != ''">
                  OR order_no = #{orderNo}
                </if>
              </when>
              <otherwise>
                order_no = #{orderNo}
              </otherwise>
            </choose>
          )
        </script>
        """)
    long countEffectiveByBizKeys(@Param("contractNo") String contractNo,
                                 @Param("orderNo") String orderNo);

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
