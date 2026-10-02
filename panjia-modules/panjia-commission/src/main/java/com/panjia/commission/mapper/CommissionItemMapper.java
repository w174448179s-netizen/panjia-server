package com.panjia.commission.mapper;

import com.panjia.commission.domain.CommissionItem;
import com.panjia.commission.domain.vo.CommissionItemDetailVo;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 结佣明细 Mapper。
 * <p>
 * CI C13：不得含 updateAmount 类方法 —— 已审批明细金额不可变，变更只能走调整单（新行）。
 */
@Mapper
public interface CommissionItemMapper extends BaseMapperPlus<CommissionItem, CommissionItem> {

    /**
     * 查询申请单下每人结佣明细详情（列口径对齐实收明细详情）。
     * <p>
     * 展示字段（工号/角色/角色名/占比/订单号/source_key 等）全部取自 pj_commission_item
     * 冻结快照，主查不再为取展示列 JOIN 事实表；事实表只用于「调整链」还原：
     * <ul>
     *   <li>PERF_EXPECT 绑定项（fact_type=PERF_EXPECT）：沿 ci.source_key 链取当前
     *       ACTIVE（新签业绩）与最早 REVERSED（调整前原值）事实；</li>
     *   <li>PERF_REAL 历史绑定项：PERF_EXPECT 与 rd 的 source_key 不同源，按
     *       「合同（订单号/合同号）+ 工号 + 角色 + 当月优先」聚合配对（rd_* CTE），
     *       配对键同样全部取自 ci 快照；</li>
     *   <li>悬空行（撤销重导后 fact_id 指向已删事实）：ci.source_key 已随重导事实回填，
     *       自然走 source_key 链配对，无需专门的悬空重配 CTE；</li>
     *   <li>DIFF 差额行（source_key 为空）仅展示结佣明细基础字段。</li>
     * </ul>
     *
     * @param applicationId 申请单 ID
     * @return 明细详情列表
     */
    @Select("""
        <script>
        WITH item_src AS (
            -- 本单非冲销明细的事实 source_key（ci 快照）
            SELECT DISTINCT source_key
            FROM pj_commission_item
            WHERE application_id = #{applicationId}
              AND status != 'REVERSED'
              AND source_key IS NOT NULL
        ),
        rd_keys AS (
            -- PERF_REAL 历史绑定项的应收配对键（全部取 ci 快照）；
            -- cur_nonzero：当月是否存在非零新签，当月优先/历史回退只算一次
            SELECT ci.period, ci.order_no, ci.contract_no,
                   ci.employee_code AS emp_code, ci.role_type,
                   EXISTS (
                       SELECT 1 FROM pj_perf_fact pc
                       WHERE pc.fact_status = 'ACTIVE' AND pc.fact_type = 'PERF_EXPECT'
                         AND pc.period = ci.period
                         AND pc.performance_amount &lt;&gt; 0
                         AND (pc.order_no = ci.order_no OR pc.contract_no = ci.contract_no)
                   ) AS cur_nonzero
            FROM pj_commission_item ci
            WHERE ci.application_id = #{applicationId}
              AND ci.status != 'REVERSED'
              AND ci.fact_type = 'PERF_REAL'
        ),
        active_expect AS (
            -- source_key 链当前 ACTIVE 新签事实（含撤销重导后悬空行按回填链命中）
            SELECT DISTINCT ON (sk.source_key) sk.source_key,
                   pe.performance_amount, pe.period AS exp_period
            FROM item_src sk
            JOIN pj_perf_fact pe ON pe.source_key = sk.source_key
               AND pe.fact_status = 'ACTIVE' AND pe.fact_type = 'PERF_EXPECT'
            ORDER BY sk.source_key, pe.id
        ),
        reversed_expect AS (
            -- source_key 链最早 REVERSED 新签事实（调整前原值）
            SELECT DISTINCT ON (sk.source_key) sk.source_key,
                   pe.performance_amount, pe.period AS exp_period
            FROM item_src sk
            JOIN pj_perf_fact pe ON pe.source_key = sk.source_key
               AND pe.fact_status = 'REVERSED' AND pe.fact_type = 'PERF_EXPECT'
            ORDER BY sk.source_key, pe.id ASC
        ),
        rd_active_expect AS (
            -- PERF_REAL 行应收聚合配对：当月有非零新签只取当月，否则取历史（实收月之前）
            SELECT rk.period, rk.order_no, rk.contract_no, rk.emp_code, rk.role_type,
                   SUM(pe.performance_amount) AS exp_amt,
                   STRING_AGG(DISTINCT pe.period, ',' ORDER BY pe.period) AS exp_period
            FROM rd_keys rk
            JOIN pj_perf_fact pe
              ON pe.fact_status = 'ACTIVE' AND pe.fact_type = 'PERF_EXPECT'
             AND (pe.order_no = rk.order_no OR pe.contract_no = rk.contract_no)
             AND pe.employee_external_code IS NOT DISTINCT FROM rk.emp_code
             AND pe.role_type IS NOT DISTINCT FROM rk.role_type
             AND ((rk.cur_nonzero AND pe.period = rk.period)
                  OR (NOT rk.cur_nonzero AND pe.period &lt; rk.period))
            GROUP BY 1, 2, 3, 4, 5
        ),
        rd_original_expect AS (
            -- PERF_REAL 行调整前原值：ACTIVE 链取同链最早 REVERSED 金额，当月/历史口径同上
            SELECT rk.period, rk.order_no, rk.contract_no, rk.emp_code, rk.role_type,
                   SUM(chain.orig_amt) AS orig_amt,
                   STRING_AGG(DISTINCT chain.period, ',' ORDER BY chain.period) AS orig_period
            FROM rd_keys rk
            JOIN (
                SELECT DISTINCT ON (a.source_key)
                       a.source_key, a.period, a.order_no, a.contract_no,
                       a.employee_external_code AS emp_code, a.role_type,
                       b.performance_amount AS orig_amt
                FROM pj_perf_fact a
                JOIN pj_perf_fact b ON b.source_key = a.source_key
                   AND b.fact_status = 'REVERSED' AND b.fact_type = 'PERF_EXPECT'
                WHERE a.fact_status = 'ACTIVE' AND a.fact_type = 'PERF_EXPECT'
                ORDER BY a.source_key, b.id ASC
            ) chain
              ON (chain.order_no = rk.order_no OR chain.contract_no = rk.contract_no)
             AND chain.emp_code IS NOT DISTINCT FROM rk.emp_code
             AND chain.role_type IS NOT DISTINCT FROM rk.role_type
             AND ((rk.cur_nonzero AND chain.period = rk.period)
                  OR (NOT rk.cur_nonzero AND chain.period &lt; rk.period))
            GROUP BY 1, 2, 3, 4, 5
        ),
        reversed_real AS (
            -- source_key 链最早 REVERSED 实收明细（历史口径结佣金额调整前原值）
            SELECT DISTINCT ON (sk.source_key) sk.source_key, rdr.performance_amount
            FROM item_src sk
            JOIN pj_received_detail rdr ON rdr.source_key = sk.source_key
               AND rdr.detail_status = 'REVERSED'
            ORDER BY sk.source_key, rdr.id ASC
        )
        SELECT ci.id AS "itemId",
               ci.performance_fact_id AS "factId",
               ci.employee_id AS "employeeId",
               ci.dept_id AS "deptId",
               COALESCE(ci.employee_code, e.employee_code) AS "employeeCode",
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
               ci.role_type AS "roleType",
               ci.role_name AS "roleName",
               ci.share_ratio AS "shareRatio",
               ci.biz_type AS "bizType",
               COALESCE(ae.performance_amount, rae.exp_amt) AS "expectedAmount",
               COALESCE(ae.exp_period, rae.exp_period,
                        re.exp_period, roe.orig_period,
                        f.period,
                        CASE WHEN ci.fact_type = 'PERF_REAL' THEN ci.period END) AS "expectPeriod",
               CASE
                   -- 增加角色人（ADD_MEMBER）新人事实：无 REVERSED 前序，调整前新签业绩按 0 展示
                   WHEN f.source = 'MANUAL'
                        AND (f.source_key LIKE '%|MANUAL-ADJ%' OR f.source_key LIKE '%|MANUAL-CADJ%') THEN 0
                   ELSE COALESCE(re.performance_amount, roe.orig_amt,
                        ae.performance_amount, rae.exp_amt)
               END AS "originalExpectedAmount",
               (re.source_key IS NOT NULL OR roe.orig_amt IS NOT NULL
                OR (f.source = 'MANUAL'
                    AND (f.source_key LIKE '%|MANUAL-ADJ%' OR f.source_key LIKE '%|MANUAL-CADJ%'))) AS "expectedAdjusted",
               -- 增加角色人新人行标记（新签侧 MANUAL-ADJ / 结佣侧 MANUAL-CADJ），前端展示「新增角色人」
               (f.source = 'MANUAL'
                AND (f.source_key LIKE '%|MANUAL-ADJ%' OR f.source_key LIKE '%|MANUAL-CADJ%')) AS "manualAdjust",
               ci.amount AS "amount",
               -- 结佣金额「调整前」只认结佣调整（不认新签调整）：
               -- ① 当前事实 adjust_id 命中结佣调整单：原额取同 sourceKey 链上紧邻前驱事实金额
               --    （增加角色人链无前驱，按 0）；
               -- ② sourceKey 链存在 REVERSED 实收明细（历史 rd 口径）；
               -- ③ 其余：原额=当前金额，前端只显示单值。
               CASE
                   WHEN caj.id IS NOT NULL THEN
                       COALESCE((SELECT x.performance_amount
                                 FROM pj_perf_fact x
                                 WHERE x.source_key = f.source_key
                                   AND x.fact_type = 'PERF_EXPECT'
                                   AND x.id &lt; f.id
                                 ORDER BY x.id DESC
                                 LIMIT 1), 0)
                   WHEN rr.source_key IS NOT NULL THEN rr.performance_amount
                   ELSE ci.amount
               END AS "originalAmount",
               (caj.id IS NOT NULL OR rr.source_key IS NOT NULL) AS "receivedAdjusted",
               ci.fee_item AS "feeItem",
               ci.status AS "status"
        FROM pj_commission_item ci
        -- 绑定事实仅用于调整链：f.adjust_id 判结佣调整、同链前驱取原值、MANUAL 新人标记
        LEFT JOIN pj_perf_fact f ON f.id = ci.performance_fact_id
        LEFT JOIN pj_commission_adjust caj ON caj.id = f.adjust_id
        LEFT JOIN active_expect ae ON ci.fact_type = 'PERF_EXPECT'
              AND ae.source_key = ci.source_key
        LEFT JOIN reversed_expect re ON ci.fact_type = 'PERF_EXPECT'
              AND re.source_key = ci.source_key
        LEFT JOIN reversed_real rr ON rr.source_key = ci.source_key
        LEFT JOIN rd_active_expect rae
               ON ci.fact_type = 'PERF_REAL'
              AND rae.period IS NOT DISTINCT FROM ci.period
              AND (rae.order_no = ci.order_no OR rae.contract_no = ci.contract_no)
              AND rae.emp_code IS NOT DISTINCT FROM ci.employee_code
              AND rae.role_type IS NOT DISTINCT FROM ci.role_type
        LEFT JOIN rd_original_expect roe
               ON ci.fact_type = 'PERF_REAL'
              AND roe.period IS NOT DISTINCT FROM ci.period
              AND (roe.order_no = ci.order_no OR roe.contract_no = ci.contract_no)
              AND roe.emp_code IS NOT DISTINCT FROM ci.employee_code
              AND roe.role_type IS NOT DISTINCT FROM ci.role_type
        LEFT JOIN pj_people_employee e ON e.employee_id = ci.employee_id
        LEFT JOIN sys_dept d ON d.dept_id = ci.dept_id
        LEFT JOIN sys_dept p ON p.dept_id = d.parent_id
        LEFT JOIN sys_dept gp ON gp.dept_id = p.parent_id
        WHERE ci.application_id = #{applicationId}
          AND ci.status != 'REVERSED'
        ORDER BY e.employee_name, d.dept_id, ci.role_type, ci.id
        </script>
        """)
    List<CommissionItemDetailVo> selectItemDetails(@Param("applicationId") Long applicationId);

    /**
     * 按结佣明细 ID 批量查业务类型（itemId → bizType）。
     * <p>
     * 结佣调整单列表 / 详情展示折算后金额时使用：调整单本身不存 bizType，
     * 由其关联的结佣明细反查（结佣域自有表，不跨域）。折算比例由
     * {@code ConversionFactorPort} 统一提供，本 Mapper 不直连规则表。
     *
     * @param itemIds 结佣明细 ID 集合（非空）
     * @return 每行含 itemId / bizType
     */
    @Select("""
        <script>
        SELECT ci.id AS "itemId", ci.biz_type AS "bizType"
        FROM pj_commission_item ci
        WHERE ci.id IN
        <foreach collection="itemIds" item="iid" open="(" separator="," close=")">#{iid}</foreach>
        </script>
    """)
    List<Map<String, Object>> selectBizTypeByItemIds(@Param("itemIds") Collection<Long> itemIds);


    /**
     * 批量查「当前存在已生效增加角色人」的合同/订单键。
     * <p>
     * 判定口径与新签合同列表一致：同期间存在 ACTIVE 且 source=MANUAL、
     * sourceKey 带 MANUAL-ADJ（新签侧发起）/ MANUAL-CADJ（结佣侧发起）标记的 PERF_EXPECT 事实。
     * 合同号/订单号双键任一命中即返回该键（一手房等以订单号为准）。
     *
     * @param period 归属期间
     * @param keys   当前页合同号/订单号集合
     * @return 命中的业务键集合（合同号与订单号混合）
     */
    @Select("""
        <script>
        SELECT DISTINCT k.biz_key
        FROM (
            SELECT contract_no AS biz_key FROM pj_perf_fact
            WHERE fact_status = 'ACTIVE' AND fact_type = 'PERF_EXPECT' AND period = #{period}
              AND source = 'MANUAL'
              AND (source_key LIKE '%|MANUAL-ADJ%' OR source_key LIKE '%|MANUAL-CADJ%')
              AND contract_no IN
              <foreach collection="keys" item="bk" open="(" separator="," close=")">#{bk}</foreach>
            UNION
            SELECT order_no AS biz_key FROM pj_perf_fact
            WHERE fact_status = 'ACTIVE' AND fact_type = 'PERF_EXPECT' AND period = #{period}
              AND source = 'MANUAL'
              AND (source_key LIKE '%|MANUAL-ADJ%' OR source_key LIKE '%|MANUAL-CADJ%')
              AND order_no IN
              <foreach collection="keys" item="bk" open="(" separator="," close=")">#{bk}</foreach>
        ) k
        WHERE k.biz_key IS NOT NULL AND k.biz_key &lt;&gt; ''
        </script>
        """)
    List<String> selectAddMemberBizKeys(@Param("period") String period, @Param("keys") Collection<String> keys);
}
