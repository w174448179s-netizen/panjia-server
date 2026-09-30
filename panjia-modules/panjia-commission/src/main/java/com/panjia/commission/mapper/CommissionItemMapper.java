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
     * 拆表后 {@code ci.performance_fact_id} 可能指向两类事实：
     * 新签口径绑定 {@code pj_perf_fact} 的 PERF_EXPECT 行；历史/实收口径绑定
     * {@code pj_received_detail(rd2)}（JOIN {@code pj_received_contract(rc2)} 取合同键）。
     * 主查双 LEFT JOIN 后各展示列 COALESCE 两侧取值。
     * <p>
     * 应收配对两套口径：fact 绑定项沿 source_key 链配对 PERF_EXPECT
     * （active/reversed CTE）；rd 绑定项因 rd.source_key（order|工号|期间|日期）与
     * PERF_EXPECT source_key 不同源，按「合同（订单号/合同号）+ 员工工号 + 角色」
     * 聚合配对（与 selectReceivedFactDetails 同口径）。
     * rd 绑定项的实收调整链沿 rd.source_key 取最早 REVERSED 行（reversed_real）。
     * DIFF 差额行（performance_fact_id 为空）仅展示结佣明细基础字段。
     *
     * @param applicationId 申请单 ID
     * @return 明细详情列表
     */
    @Select("""
        <script>
        WITH src_keys AS (
            SELECT DISTINCT f.source_key
            FROM pj_commission_item ci2
            JOIN pj_perf_fact f ON f.id = ci2.performance_fact_id
            WHERE ci2.application_id = #{applicationId}
              AND ci2.status != 'REVERSED'
              AND f.source_key IS NOT NULL
        ),
        rd_keys AS (
            SELECT DISTINCT rd3.source_key, rd3.period,
                   rc3.order_no, rc3.contract_no,
                   rd3.employee_external_code AS emp_code, rd3.role_type
            FROM pj_commission_item ci3
            JOIN pj_received_detail rd3 ON rd3.id = ci3.performance_fact_id
            JOIN pj_received_contract rc3 ON rc3.id = rd3.contract_id
            WHERE ci3.application_id = #{applicationId}
              AND ci3.status != 'REVERSED'
              AND rd3.source_key IS NOT NULL
        ),
        reversed_expect AS (
            SELECT DISTINCT ON (sk.source_key) sk.source_key, pe.performance_amount
            FROM src_keys sk
            JOIN pj_perf_fact pe ON pe.source_key = sk.source_key
               AND pe.fact_status = 'REVERSED' AND pe.fact_type = 'PERF_EXPECT'
            ORDER BY sk.source_key, pe.id ASC
        ),
        active_expect AS (
            SELECT DISTINCT ON (sk.source_key) sk.source_key, pe.performance_amount
            FROM src_keys sk
            JOIN pj_perf_fact pe ON pe.source_key = sk.source_key
               AND pe.fact_status = 'ACTIVE' AND pe.fact_type = 'PERF_EXPECT'
            ORDER BY sk.source_key, pe.id
        ),
        rd_pair AS (
            -- 配对键去重：同合同同人同角色当月可能有多条 rd（多到账日），应收额只能计一次
            SELECT DISTINCT period, order_no, contract_no, emp_code, role_type
            FROM rd_keys
        ),
        rd_active_expect AS (
            SELECT rp.period, rp.order_no, rp.contract_no, rp.emp_code, rp.role_type,
                   SUM(pe.performance_amount) AS exp_amt
            FROM rd_pair rp
            JOIN pj_perf_fact pe
              ON pe.fact_status = 'ACTIVE' AND pe.fact_type = 'PERF_EXPECT'
             AND (pe.order_no = rp.order_no OR pe.contract_no = rp.contract_no)
             AND pe.employee_external_code IS NOT DISTINCT FROM rp.emp_code
             AND pe.role_type IS NOT DISTINCT FROM rp.role_type
             -- 当月优先口径：当月有非零新签 → 只取当月；当月为 0/无 → 取历史（实收月之前）
             AND (
                   (pe.period = rp.period AND EXISTS (
                       SELECT 1 FROM pj_perf_fact pc
                       WHERE pc.fact_status = 'ACTIVE' AND pc.fact_type = 'PERF_EXPECT'
                         AND pc.period = rp.period
                         AND pc.performance_amount != 0
                         AND (pc.order_no = rp.order_no OR pc.contract_no = rp.contract_no)))
                OR (pe.period &lt; rp.period AND NOT EXISTS (
                       SELECT 1 FROM pj_perf_fact pc
                       WHERE pc.fact_status = 'ACTIVE' AND pc.fact_type = 'PERF_EXPECT'
                         AND pc.period = rp.period
                         AND pc.performance_amount != 0
                         AND (pc.order_no = rp.order_no OR pc.contract_no = rp.contract_no)))
                 )
            GROUP BY 1, 2, 3, 4, 5
        ),
        rd_original_expect AS (
            SELECT rp.period, rp.order_no, rp.contract_no, rp.emp_code, rp.role_type,
                   SUM(chain.orig_amt) AS orig_amt
            FROM rd_pair rp
            JOIN (
                -- 每条 ACTIVE 应收 sourceKey 链取最早一条 REVERSED 金额（同 selectReceivedFactDetails）
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
              ON (chain.order_no = rp.order_no OR chain.contract_no = rp.contract_no)
             AND chain.emp_code IS NOT DISTINCT FROM rp.emp_code
             AND chain.role_type IS NOT DISTINCT FROM rp.role_type
             -- 与 rd_active_expect 同口径：当月有非零新签取当月链，否则取历史链
             AND (
                   (chain.period = rp.period AND EXISTS (
                       SELECT 1 FROM pj_perf_fact pc
                       WHERE pc.fact_status = 'ACTIVE' AND pc.fact_type = 'PERF_EXPECT'
                         AND pc.period = rp.period
                         AND pc.performance_amount != 0
                         AND (pc.order_no = rp.order_no OR pc.contract_no = rp.contract_no)))
                OR (chain.period &lt; rp.period AND NOT EXISTS (
                       SELECT 1 FROM pj_perf_fact pc
                       WHERE pc.fact_status = 'ACTIVE' AND pc.fact_type = 'PERF_EXPECT'
                         AND pc.period = rp.period
                         AND pc.performance_amount != 0
                         AND (pc.order_no = rp.order_no OR pc.contract_no = rp.contract_no)))
                 )
            GROUP BY 1, 2, 3, 4, 5
        ),
        reversed_real AS (
            SELECT DISTINCT ON (k.source_key) k.source_key, rdr.performance_amount
            FROM (
                SELECT source_key FROM src_keys
                UNION
                SELECT source_key FROM rd_keys
            ) k
            JOIN pj_received_detail rdr ON rdr.source_key = k.source_key
               AND rdr.detail_status = 'REVERSED'
            ORDER BY k.source_key, rdr.id ASC
        )
        SELECT ci.id AS "itemId",
               ci.performance_fact_id AS "factId",
               ci.employee_id AS "employeeId",
               COALESCE(e.employee_code, f.employee_external_code, rd2.employee_external_code) AS "employeeCode",
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
               COALESCE(f.role_type, rd2.role_type, ci.role_type) AS "roleType",
               COALESCE(f.role_name, rd2.role_name) AS "roleName",
               COALESCE(f.share_ratio, rd2.share_ratio) AS "shareRatio",
               ci.biz_type AS "bizType",
               COALESCE(ae.performance_amount, rae.exp_amt) AS "expectedAmount",
               COALESCE(re.performance_amount, roe.orig_amt,
                        ae.performance_amount, rae.exp_amt) AS "originalExpectedAmount",
               (re.source_key IS NOT NULL OR roe.orig_amt IS NOT NULL) AS "expectedAdjusted",
               ci.amount AS "amount",
               COALESCE(re.performance_amount, rr.performance_amount, roe.orig_amt,
                        ae.performance_amount, rae.exp_amt,
                        f.performance_amount, rd2.performance_amount, ci.amount) AS "originalAmount",
               (re.source_key IS NOT NULL OR rr.source_key IS NOT NULL) AS "receivedAdjusted",
               ci.fee_item AS "feeItem",
               ci.status AS "status"
        FROM pj_commission_item ci
        LEFT JOIN pj_perf_fact f ON f.id = ci.performance_fact_id
        LEFT JOIN pj_received_detail rd2 ON rd2.id = ci.performance_fact_id
        LEFT JOIN pj_received_contract rc2 ON rc2.id = rd2.contract_id
        LEFT JOIN active_expect ae ON ae.source_key = f.source_key
        LEFT JOIN reversed_expect re ON re.source_key = f.source_key
        LEFT JOIN reversed_real rr ON rr.source_key = COALESCE(rd2.source_key, f.source_key)
        LEFT JOIN rd_active_expect rae
               ON rae.period IS NOT DISTINCT FROM rd2.period
              AND rd2.contract_id IS NOT NULL
              AND (rae.order_no = rc2.order_no OR rae.contract_no = rc2.contract_no)
              AND rae.emp_code IS NOT DISTINCT FROM rd2.employee_external_code
              AND rae.role_type IS NOT DISTINCT FROM rd2.role_type
        LEFT JOIN rd_original_expect roe
               ON roe.period IS NOT DISTINCT FROM rd2.period
              AND rd2.contract_id IS NOT NULL
              AND (roe.order_no = rc2.order_no OR roe.contract_no = rc2.contract_no)
              AND roe.emp_code IS NOT DISTINCT FROM rd2.employee_external_code
              AND roe.role_type IS NOT DISTINCT FROM rd2.role_type
        LEFT JOIN pj_people_employee e ON e.employee_id = ci.employee_id
        LEFT JOIN sys_dept d ON d.dept_id = ci.dept_id
        LEFT JOIN sys_dept p ON p.dept_id = d.parent_id
        LEFT JOIN sys_dept gp ON gp.dept_id = p.parent_id
        WHERE ci.application_id = #{applicationId}
          AND ci.status != 'REVERSED'
        ORDER BY e.employee_name, d.dept_id, COALESCE(f.role_type, rd2.role_type), ci.id
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
}
