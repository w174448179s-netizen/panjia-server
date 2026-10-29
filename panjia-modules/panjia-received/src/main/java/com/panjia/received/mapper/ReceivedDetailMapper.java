package com.panjia.received.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.panjia.received.domain.ReceivedDetail;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.math.BigDecimal;
import java.util.Collection;
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

    /**
     * 批量取合同级 ACTIVE 明细聚合（period_total_received / item_count 的重算依据）。
     * <p>
     * 导入重导 / 跨部门冲销时旧批次明细已置 SUPERSEDED，合同合计必须按 ACTIVE 明细重算，
     * 不能在旧值上增量累加，否则旧批金额会被重复计入。
     */
    @Select("""
        <script>
        SELECT rd.contract_id AS "contractId",
               COALESCE(SUM(rd.performance_amount), 0) AS "totalAmount",
               COUNT(*) AS "itemCount"
        FROM pj_received_detail rd
        WHERE rd.detail_status = 'ACTIVE'
          AND rd.contract_id IN
          <foreach collection="contractIds" item="id" open="(" separator="," close=")">#{id}</foreach>
        GROUP BY rd.contract_id
        </script>
        """)
    List<Map<String, Object>> selectActiveAggregatesByContractIds(@Param("contractIds") Collection<Long> contractIds);

    /**
     * 取批次内 ACTIVE 明细涉及的合同 ID（冲销前快照，用于冲销后重算这些合同的聚合）。
     */
    @Select("""
        <script>
        SELECT DISTINCT rd.contract_id
        FROM pj_received_detail rd
        WHERE rd.detail_status = 'ACTIVE'
          AND rd.source_batch_id IN
          <foreach collection="batchIds" item="id" open="(" separator="," close=")">#{id}</foreach>
        </script>
        """)
    List<Long> selectActiveContractIdsByBatchIds(@Param("batchIds") Collection<Long> batchIds);

    /**
     * 「待匹配新签」到账组：未绑定审批单的 ACTIVE 实收明细，按业务键(订单号优先/合同号) + 归属月聚合。
     * <p>
     * 去草稿（2026-10-29）后无新签的到账只落库、不建单，审批单列表里看不到；
     * 列表据此拼出「待匹配新签」行，用户在该行补录新签，审批通过后走自动补建单链路。
     * 员工筛选按明细口径只统计该员工自己的金额（与单据行一致）；门店筛选按组 EXISTS
     * 过滤整组（不改变组内金额，与单据行口径一致）。
     */
    @Select("""
        <script>
        WITH grp AS (
            SELECT COALESCE(rc.order_no, rc.contract_no) AS biz_key,
                   MAX(rc.contract_no) AS contract_no,
                   MAX(rc.order_no) AS order_no,
                   MAX(rc.biz_type) AS biz_type,
                   MAX(rc.property_address) AS property_address,
                   MAX(rc.business_date) AS business_date,
                   rc.period AS period,
                   COALESCE(SUM(rd.performance_amount), 0) AS received_amount,
                   COUNT(*) AS item_count,
                   COUNT(DISTINCT COALESCE(rd.employee_id::text, e.employee_id::text, rd.employee_external_code))
                       AS employee_count
            FROM pj_received_detail rd
            JOIN pj_received_contract rc ON rc.id = rd.contract_id
            LEFT JOIN pj_people_employee e
                   ON (e.employee_id = rd.employee_id
                       OR (rd.employee_id IS NULL AND e.employee_code = rd.employee_external_code))
            WHERE rd.detail_status = 'ACTIVE'
              AND rd.received_apply_id IS NULL
              AND COALESCE(rc.order_no, rc.contract_no) IS NOT NULL
              <if test="employeeId != null">AND COALESCE(rd.employee_id, e.employee_id) = #{employeeId}</if>
              /* 该业务键+归属月已有生效审批单时不算「待匹配」（新签已到、单已建/在审，缺的明细走调整/重导） */
              AND NOT EXISTS (
                  SELECT 1 FROM pj_perf_received_apply ra
                  WHERE ra.period = rc.period
                    AND ra.status IN ('SUBMITTED', 'APPROVED')
                    AND (ra.order_no = rc.order_no
                         OR (rc.order_no IS NULL AND ra.contract_no = rc.contract_no))
              )
            GROUP BY COALESCE(rc.order_no, rc.contract_no), rc.period
        )
        SELECT grp.contract_no AS "contractNo",
               grp.order_no AS "orderNo",
               grp.biz_type AS "bizType",
               grp.property_address AS "propertyAddress",
               grp.business_date AS "businessDate",
               grp.period AS "period",
               grp.received_amount AS "receivedAmount",
               grp.item_count AS "itemCount",
               grp.employee_count AS "employeeCount"
        FROM grp
        WHERE 1 = 1
        <if test="period != null and period != ''">AND grp.period = #{period}</if>
        <if test="bizType != null and bizType != ''">AND grp.biz_type = #{bizType}</if>
        <if test="keyword != null and keyword != ''">
          AND (grp.contract_no LIKE CONCAT('%', #{keyword}::text, '%')
               OR grp.order_no LIKE CONCAT('%', #{keyword}::text, '%')
               OR grp.property_address LIKE CONCAT('%', #{keyword}::text, '%'))
        </if>
        <if test="deptId != null">
          AND EXISTS (
              SELECT 1
              FROM pj_received_detail d2
              JOIN pj_received_contract c2 ON c2.id = d2.contract_id
              LEFT JOIN pj_people_employee e2
                     ON (e2.employee_id = d2.employee_id
                         OR (d2.employee_id IS NULL AND e2.employee_code = d2.employee_external_code))
              LEFT JOIN sys_dept sd ON sd.dept_id = COALESCE(d2.dept_id, c2.dept_id, e2.dept_id)
              WHERE COALESCE(c2.order_no, c2.contract_no) = grp.biz_key
                AND c2.period = grp.period
                AND d2.detail_status = 'ACTIVE'
                AND d2.received_apply_id IS NULL
                AND (sd.dept_id = #{deptId}
                     OR sd.ancestors LIKE CONCAT('%', #{deptId}, '%'))
          )
        </if>
        ORDER BY grp.business_date DESC, grp.biz_key
        </script>
        """)
    List<Map<String, Object>> selectPendingGroups(@Param("period") String period,
                                                  @Param("deptId") Long deptId,
                                                  @Param("keyword") String keyword,
                                                  @Param("bizType") String bizType,
                                                  @Param("employeeId") Long employeeId);

    /**
     * 「待匹配新签」组的每人明细（未绑定审批单的 ACTIVE 实收明细）。
     * <p>供补录弹窗装载角色人清单；列口径对齐 {@code ReceivedFactDetailVo}。
     * bizKey = 业务键（订单号优先，空订单号的老数据按合同号）；period 可空：空时返回该业务键全部归属月。
     */
    @Select("""
        <script>
        SELECT rd.id AS "factId",
               rd.period AS "period",
               COALESCE(e.employee_id, rd.employee_id) AS "employeeId",
               COALESCE(e.employee_code, rd.employee_external_code) AS "employeeCode",
               e.employee_name AS "employeeName",
               CASE
                   WHEN array_length(string_to_array(d.ancestors, ','), 1) &gt;= 3 THEN
                       CONCAT_WS('-',
                           NULLIF(gp.dept_name, 'tenant_name'),
                           NULLIF(p.dept_name, 'tenant_name'),
                           CASE WHEN d.dept_name = p.dept_name THEN NULL
                                ELSE NULLIF(d.dept_name, 'tenant_name') END)
                   ELSE
                       CONCAT_WS('-',
                           NULLIF(p.dept_name, 'tenant_name'),
                           NULLIF(d.dept_name, 'tenant_name'))
               END AS "deptPath",
               rd.role_type AS "roleType",
               rd.role_name AS "roleName",
               rd.share_ratio AS "shareRatio",
               rd.performance_amount AS "amount",
               rd.performance_amount AS "originalAmount",
               FALSE AS "receivedAdjusted"
        FROM pj_received_detail rd
        JOIN pj_received_contract rc ON rc.id = rd.contract_id
        LEFT JOIN pj_people_employee e
               ON (e.employee_id = rd.employee_id
                   OR (rd.employee_id IS NULL AND e.employee_code = rd.employee_external_code))
        LEFT JOIN sys_dept d ON d.dept_id = COALESCE(rd.dept_id, rc.dept_id, e.dept_id)
        LEFT JOIN sys_dept p ON p.dept_id = d.parent_id
        LEFT JOIN sys_dept gp ON gp.dept_id = p.parent_id
        WHERE rd.detail_status = 'ACTIVE'
          AND rd.received_apply_id IS NULL
          AND (rc.order_no = #{bizKey} OR (rc.order_no IS NULL AND rc.contract_no = #{bizKey}))
          <if test="period != null and period != ''">AND rd.period = #{period}</if>
        ORDER BY e.employee_name, rd.role_type, rd.id
        </script>
        """)
    List<com.panjia.performance.domain.vo.ReceivedFactDetailVo> selectPendingDetails(
        @Param("bizKey") String bizKey, @Param("period") String period);
}

