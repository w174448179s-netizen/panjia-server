package com.panjia.received.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.panjia.received.domain.ReceivedDetail;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 实收明细 Mapper。
 * <p>
 * 单表等值查询一律用 {@code LambdaQueryWrapper + BaseMapper.selectOne/selectCount}，
 * 不声明自定义方法；需要 JOIN / 聚合 / 动态 SQL 时再按仓库注解 SQL 规范加 @Select。
 */
@Mapper
public interface ReceivedDetailMapper extends BaseMapper<ReceivedDetail> {

    /**
     * 按员工口径合计实收金额：指定单据集合内该员工的 ACTIVE 明细合计。
     * <p>
     * 用于实收明细列表按员工筛选时的汇总条——单据级 received_amount 含同合同其他
     * 角色人金额，员工筛选时必须只统计该员工自己的明细。
     * 员工匹配口径与列表筛选一致：employee_id 直连，为空时按外部工号匹配员工主数据。
     */
    @Select("""
        <script>
        SELECT COALESCE(SUM(rd.performance_amount), 0)
        FROM pj_received_detail rd
        JOIN pj_people_employee e ON (e.employee_id = rd.employee_id
           OR (NOT EXISTS (SELECT 1 FROM pj_people_employee x WHERE x.employee_id = rd.employee_id)
               AND e.employee_code = rd.employee_external_code))
        WHERE rd.detail_status = 'ACTIVE'
          AND e.employee_id = #{employeeId}
          AND rd.received_apply_id IN
          <foreach collection="applyIds" item="id" open="(" separator="," close=")">#{id}</foreach>
        </script>
        """)
    BigDecimal sumAmountByApplyIdsAndEmployee(@Param("applyIds") List<Object> applyIds,
                                              @Param("employeeId") Long employeeId);

    /**
     * 按员工口径返回每张审批单的该员工实收/应收合计（列表行展示用）。
     * <p>
     * 返回每单一行：applyId / bizType / receivedAmount / expectedAmount。
     * 实收取 pj_received_detail 该员工 ACTIVE 明细合计（员工匹配口径同列表筛选：employee_id
     * 直连、为空回退外部工号）；应收取 pj_perf_fact PERF_EXPECT 该员工份额，按单据业务键
     * （订单号/合同号）配对，期间口径为「实收月及之前」（与详情「每人实收明细」一致）。
     * 合同/订单金额含同合同其他角色人金额，员工筛选时须用本结果覆盖单据级金额。
     */
    @Select("""
        <script>
        WITH src AS (
            SELECT id AS apply_id, period,
                   COALESCE(contract_no, '') AS ck, COALESCE(order_no, '') AS ok, biz_type
            FROM pj_perf_received_apply
            WHERE id IN
            <foreach collection="applyIds" item="id" open="(" separator="," close=")">#{id}</foreach>
        ),
        rd_agg AS (
            SELECT rd.received_apply_id AS apply_id,
                   COALESCE(SUM(rd.performance_amount), 0) AS received_amount
            FROM pj_received_detail rd
            JOIN pj_people_employee e ON (e.employee_id = rd.employee_id
               OR (NOT EXISTS (SELECT 1 FROM pj_people_employee x WHERE x.employee_id = rd.employee_id)
                   AND e.employee_code = rd.employee_external_code))
            WHERE rd.detail_status = 'ACTIVE'
              AND e.employee_id = #{employeeId}
              AND rd.received_apply_id IN
              <foreach collection="applyIds" item="id" open="(" separator="," close=")">#{id}</foreach>
            GROUP BY rd.received_apply_id
        ),
        exp_agg AS (
            SELECT s.apply_id,
                   COALESCE(SUM(pe.performance_amount), 0) AS expected_amount
            FROM src s
            JOIN pj_perf_fact pe ON pe.fact_status = 'ACTIVE' AND pe.fact_type = 'PERF_EXPECT'
                 AND pe.employee_id = #{employeeId}
                 AND (
                       (s.ok &lt;&gt; '' AND s.ck &lt;&gt; '' AND pe.contract_no = s.ck AND pe.order_no = s.ok)
                    OR (s.ck = '' AND s.ok &lt;&gt; '' AND pe.order_no = s.ok)
                    OR (s.ok = '' AND s.ck &lt;&gt; '' AND (pe.contract_no = s.ck OR pe.order_no = s.ck))
                     )
                 AND (s.period IS NULL OR s.period = '' OR pe.period &lt;= s.period)
            GROUP BY s.apply_id
        )
        SELECT s.apply_id AS "applyId",
               s.biz_type AS "bizType",
               COALESCE(rd_agg.received_amount, 0) AS "receivedAmount",
               COALESCE(exp_agg.expected_amount, 0) AS "expectedAmount"
        FROM src s
        LEFT JOIN rd_agg ON rd_agg.apply_id = s.apply_id
        LEFT JOIN exp_agg ON exp_agg.apply_id = s.apply_id
        </script>
        """)
    List<Map<String, Object>> selectEmployeeAmountsByApplyIds(@Param("applyIds") List<Object> applyIds,
                                                             @Param("employeeId") Long employeeId);
}

