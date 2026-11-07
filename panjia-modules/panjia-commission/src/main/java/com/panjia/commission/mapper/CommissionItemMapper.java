package com.panjia.commission.mapper;

import com.panjia.commission.domain.CommissionItem;
import com.panjia.commission.domain.vo.CommissionItemDetailVo;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;

import java.util.Collection;
import java.util.List;

/**
 * 结佣明细 Mapper。
 * <p>
 * CI C13：不得含 updateAmount 类方法 —— 已审批明细金额不可变，变更需先撤销后重新发起。
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
        app_info AS (
            -- 本单实收归属窗口（2026-11-02 定稿，取消锁定冻结）：
            -- 窗口键 = 归一业务键 + 单据方向（正/负单各自成链），与结佣列表/发起页归属算法一致；
            -- 每笔实收按到账月归属「期间不晚于该月」的最晚一张同向单；
            -- 本单窗口 = [本单期间, 下一张同向单期间)，首张同向单窗口下界不限（承接更早到账）
            SELECT x.period, x.biz_key, x.is_neg,
                   (SELECT COUNT(*)
                      FROM pj_commission_application b
                     WHERE b.period &lt; x.period
                       AND b.status IN ('SUBMITTED','APPROVED','LOCKED')
                       AND (COALESCE(b.total_amount, 0) &lt; 0) = x.is_neg
                       AND (CASE WHEN x.order_first
                                 THEN COALESCE(NULLIF(b.order_no,''), NULLIF(b.contract_no,''))
                                 ELSE COALESCE(NULLIF(b.contract_no,''), NULLIF(b.order_no,'')) END)
                           = x.biz_key) = 0 AS is_first,
                   -- 窗口上界 = 下一张同向单期间，与「正向前置补收月」取小（2026-11-03 补收单独成行）：
                   -- 补收月（到账月无正向新签、且此前已发生退单后的正向到账）不属于任何结佣单，
                   -- 提前截断窗口，避免补收金额并入旧正向单（与结佣列表补收行口径一致）
                   LEAST(
                       (SELECT MIN(b.period)
                          FROM pj_commission_application b
                         WHERE b.period &gt; x.period
                           AND b.status IN ('SUBMITTED','APPROVED','LOCKED')
                           AND (COALESCE(b.total_amount, 0) &lt; 0) = x.is_neg
                           AND (CASE WHEN x.order_first
                                     THEN COALESCE(NULLIF(b.order_no,''), NULLIF(b.contract_no,''))
                                     ELSE COALESCE(NULLIF(b.contract_no,''), NULLIF(b.order_no,'')) END)
                               = x.biz_key),
                       (SELECT MIN(rc.period)
                          FROM pj_received_detail rd
                          JOIN pj_received_contract rc ON rc.id = rd.contract_id
                          LEFT JOIN pj_perf_received_apply ra ON ra.id = rd.received_apply_id
                         WHERE rd.detail_status = 'ACTIVE'
                           AND rd.performance_amount &gt; 0
                           AND (rd.received_apply_id IS NULL OR ra.status = 'APPROVED')
                           AND (CASE WHEN x.order_first
                                     THEN COALESCE(NULLIF(rc.order_no,''), NULLIF(rc.contract_no,''))
                                     ELSE COALESCE(NULLIF(rc.contract_no,''), NULLIF(rc.order_no,'')) END)
                               = x.biz_key
                           AND NOT EXISTS (
                               SELECT 1 FROM pj_perf_fact pe
                                WHERE pe.fact_status = 'ACTIVE' AND pe.fact_type = 'PERF_EXPECT'
                                  AND pe.performance_amount &gt; 0 AND pe.period = rc.period
                                  AND (CASE WHEN x.order_first
                                            THEN COALESCE(NULLIF(pe.order_no,''), NULLIF(pe.contract_no,''))
                                            ELSE COALESCE(NULLIF(pe.contract_no,''), NULLIF(pe.order_no,'')) END)
                                      = x.biz_key)
                           AND EXISTS (
                               SELECT 1 FROM pj_perf_fact nn
                                WHERE nn.fact_status = 'ACTIVE' AND nn.fact_type = 'PERF_EXPECT'
                                  AND nn.performance_amount &lt; 0 AND nn.period &lt;= rc.period
                                  AND (CASE WHEN x.order_first
                                            THEN COALESCE(NULLIF(nn.order_no,''), NULLIF(nn.contract_no,''))
                                            ELSE COALESCE(NULLIF(nn.contract_no,''), NULLIF(nn.order_no,'')) END)
                                      = x.biz_key)
                       )
                   ) AS next_period
            FROM (
                SELECT a.period,
                       t.biz_type IN ('一手房','房产金融','家装荐客') AS order_first,
                       CASE WHEN t.biz_type IN ('一手房','房产金融','家装荐客')
                            THEN COALESCE(NULLIF(a.order_no,''), NULLIF(a.contract_no,''))
                            ELSE COALESCE(NULLIF(a.contract_no,''), NULLIF(a.order_no,'')) END AS biz_key,
                       (COALESCE(a.total_amount, 0) &lt; 0) AS is_neg
                FROM pj_commission_application a
                LEFT JOIN (
                    SELECT MAX(ci.biz_type) AS biz_type
                    FROM pj_commission_item ci
                    WHERE ci.application_id = #{applicationId}
                ) t ON TRUE
                WHERE a.id = #{applicationId}
            ) x
        ),
        active_received AS (
            -- 当前「有效」ACTIVE 实收明细按业务键+员工+角色+期间聚合；
            -- 有效 = 未绑定审批单或审批单已通过（SUBMITTED/DRAFT 未审批不计入）；
            -- 按本单到账归属窗口切片，锁定后新增实收继续计入；
            -- recv_pos_amt=当期正向合计，recv_neg_amt=当期负部（冲正/退单行展示用，当期无负数时为 NULL）；
            -- 正负分开累计，避免冲正被并入正向行
            SELECT CASE WHEN rc.biz_type IN ('一手房','房产金融','家装荐客')
                        THEN COALESCE(NULLIF(rc.order_no,''), NULLIF(rc.contract_no,''))
                        ELSE COALESCE(NULLIF(rc.contract_no,''), NULLIF(rc.order_no,'')) END AS biz_key,
                   COALESCE(rd.employee_id, e.employee_id) AS employee_id,
                   COALESCE(rd.employee_external_code, e.employee_code) AS emp_code,
                   rd.role_type, rd.period,
                   SUM(rd.performance_amount) FILTER (WHERE rd.performance_amount &gt; 0) AS recv_pos_amt,
                   SUM(rd.performance_amount) FILTER (WHERE rd.performance_amount &lt; 0) AS recv_neg_amt
            FROM pj_received_detail rd
            JOIN pj_received_contract rc ON rc.id = rd.contract_id
            LEFT JOIN pj_people_employee e
                   ON (e.employee_id = rd.employee_id
                       OR (rd.employee_id IS NULL AND e.employee_code = rd.employee_external_code))
            CROSS JOIN app_info app
            LEFT JOIN pj_perf_received_apply ra ON ra.id = rd.received_apply_id
            WHERE rd.detail_status = 'ACTIVE'
              AND (CASE WHEN rc.biz_type IN ('一手房','房产金融','家装荐客')
                        THEN COALESCE(NULLIF(rc.order_no,''), NULLIF(rc.contract_no,''))
                        ELSE COALESCE(NULLIF(rc.contract_no,''), NULLIF(rc.order_no,'')) END) = app.biz_key
              AND (rd.received_apply_id IS NULL OR ra.status = 'APPROVED')
              AND (app.is_first OR rd.period &gt;= app.period)
              AND (app.next_period IS NULL OR rd.period &lt; app.next_period)
            GROUP BY 1, 2, 3, 4, 5
        ),
        reversed_rd_chain AS (
            -- rd 全量链最早 REVERSED 金额（实收调整 supersede 链原值，一次预聚合替代逐行相关子查询）
            SELECT DISTINCT ON (source_key) source_key, performance_amount
            FROM pj_received_detail
            WHERE detail_status = 'REVERSED'
            ORDER BY source_key, id ASC
        ),
        original_received AS (
            -- 与 active_received 同配对/同有效口径/同到账窗口的 ACTIVE rd 行，逐行取链原值求和；
            -- orig_pos_amt=当期正向行链原值，orig_neg_amt=当期负部行链原值
            -- （实收加人新人行 MANUAL-RADJ 无前序，按 0 计）
            SELECT CASE WHEN rc.biz_type IN ('一手房','房产金融','家装荐客')
                        THEN COALESCE(NULLIF(rc.order_no,''), NULLIF(rc.contract_no,''))
                        ELSE COALESCE(NULLIF(rc.contract_no,''), NULLIF(rc.order_no,'')) END AS biz_key,
                   COALESCE(rd.employee_id, e.employee_id) AS employee_id,
                   COALESCE(rd.employee_external_code, e.employee_code) AS emp_code,
                   rd.role_type, rd.period,
                   SUM(CASE WHEN rd.performance_amount &gt; 0 THEN
                            CASE WHEN rd.source_key LIKE '%|MANUAL-RADJ-%' THEN 0
                                 ELSE COALESCE(rrc.performance_amount, rd.performance_amount) END
                       END) AS orig_pos_amt,
                   SUM(CASE WHEN rd.performance_amount &lt; 0 THEN
                            CASE WHEN rd.source_key LIKE '%|MANUAL-RADJ-%' THEN 0
                                 ELSE COALESCE(rrc.performance_amount, rd.performance_amount) END
                       END) AS orig_neg_amt
            FROM pj_received_detail rd
            JOIN pj_received_contract rc ON rc.id = rd.contract_id
            LEFT JOIN pj_people_employee e
                   ON (e.employee_id = rd.employee_id
                       OR (rd.employee_id IS NULL AND e.employee_code = rd.employee_external_code))
            CROSS JOIN app_info app
            LEFT JOIN pj_perf_received_apply ra ON ra.id = rd.received_apply_id
            LEFT JOIN reversed_rd_chain rrc ON rrc.source_key = rd.source_key
            WHERE rd.detail_status = 'ACTIVE'
              AND (CASE WHEN rc.biz_type IN ('一手房','房产金融','家装荐客')
                        THEN COALESCE(NULLIF(rc.order_no,''), NULLIF(rc.contract_no,''))
                        ELSE COALESCE(NULLIF(rc.contract_no,''), NULLIF(rc.order_no,'')) END) = app.biz_key
              AND (rd.received_apply_id IS NULL OR ra.status = 'APPROVED')
              AND (app.is_first OR rd.period &gt;= app.period)
              AND (app.next_period IS NULL OR rd.period &lt; app.next_period)
            GROUP BY 1, 2, 3, 4, 5
        )
        SELECT ci.id AS "itemId",
               ci.performance_fact_id AS "factId",
               ci.period AS "period",
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
               -- 新签业绩：正常行按 sourceKey 配对当前 ACTIVE 事实（含调整后继任值）；
               -- 负数行（退单红冲）source_key 为 RFND- 前缀无法配对，但其 performance_fact_id
               -- 直接绑定退单生成的负新签事实，ACTIVE 时直接取该负值
               CASE WHEN ci.amount &lt; 0 AND f.fact_status = 'ACTIVE' AND f.performance_amount &lt; 0
                    THEN f.performance_amount
                    ELSE COALESCE(ae.performance_amount, rae.exp_amt) END AS "expectedAmount",
               COALESCE(ae.exp_period, rae.exp_period,
                        re.exp_period, roe.orig_period,
                        f.period,
                        CASE WHEN ci.fact_type = 'PERF_REAL' THEN ci.period END) AS "expectPeriod",
               CASE
                   -- 退单红冲负数行：原值跟随负值本身，避免出现「正 → 负」假箭头
                   WHEN ci.amount &lt; 0 AND f.fact_status = 'ACTIVE' AND f.performance_amount &lt; 0
                        THEN f.performance_amount
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
               ci.fee_item AS "feeItem",
               ci.status AS "status",
               -- 实收金额行级口径（与实收明细详情一致）：正数行=全部实收期间正向实收合并（不含冲正），
               -- 负数行（退单/冲正分多笔）=全部实收期间负向实收合并，两向互不抵消
               CASE WHEN ci.amount &lt; 0 THEN COALESCE(arc.recv_neg_all, 0)
                    ELSE COALESCE(arc.recv_pos_all, 0) END AS "receivedAmount",
               -- 配对实收行的链原值合计（同口径）：与 receivedAmount 不一致时前端展示「原值 → 调整后值」
               CASE WHEN ci.amount &lt; 0 THEN COALESCE(orc.orig_neg_all, 0)
                    ELSE COALESCE(orc.orig_pos_all, 0) END AS "originalReceivedAmount"
        FROM pj_commission_item ci
        -- 绑定事实仅用于新签调整链：同链前驱取原值、MANUAL 新人标记
        LEFT JOIN pj_perf_fact f ON f.id = ci.performance_fact_id
        LEFT JOIN active_expect ae ON ci.fact_type = 'PERF_EXPECT'
              AND ae.source_key = ci.source_key
        LEFT JOIN reversed_expect re ON ci.fact_type = 'PERF_EXPECT'
              AND re.source_key = ci.source_key
        -- 实收按方向跨期合并：正数行只合并正向实收（分多份实收相加），
        -- 负数行（退单/冲正分多笔）只合并负向实收；两向互不抵消；
        -- 业务键按同一归一规则精确匹配，避免同合同多订单串单；员工优先 employee_id，工号兜底
        LEFT JOIN LATERAL (
            SELECT SUM(a.recv_pos_amt) AS recv_pos_all,
                   SUM(a.recv_neg_amt) AS recv_neg_all
            FROM active_received a
            WHERE a.biz_key = CASE WHEN ci.biz_type IN ('一手房','房产金融','家装荐客')
                                   THEN COALESCE(NULLIF(ci.order_no,''), NULLIF(ci.contract_no,''))
                                   ELSE COALESCE(NULLIF(ci.contract_no,''), NULLIF(ci.order_no,'')) END
              AND (a.employee_id = ci.employee_id
                   OR (a.emp_code IS NOT NULL AND a.emp_code = ci.employee_code))
              -- 角色配对：正常同角色；角色对不上时，若该员工在本合同新签侧只有唯一角色，
              -- 按人兜底归到该唯一角色行（覆盖新签/实收角色命名不一致，如「合作人」vs「客源成交人」），
              -- 与业绩查询详情（PerformanceFactMapper）的配对口径一致
              AND (a.role_type IS NOT DISTINCT FROM ci.role_type
                   OR (NOT EXISTS (
                           SELECT 1 FROM pj_perf_fact f3
                           WHERE f3.fact_type = 'PERF_EXPECT' AND f3.fact_status = 'ACTIVE'
                             AND (CASE WHEN f3.biz_type IN ('一手房','房产金融','家装荐客')
                                       THEN COALESCE(NULLIF(f3.order_no,''), NULLIF(f3.contract_no,''))
                                       ELSE COALESCE(NULLIF(f3.contract_no,''), NULLIF(f3.order_no,'')) END)
                                 = a.biz_key
                             AND f3.employee_external_code IS NOT DISTINCT FROM ci.employee_code
                             AND f3.role_type IS NOT DISTINCT FROM a.role_type)
                       AND (SELECT count(DISTINCT f4.role_type) FROM pj_perf_fact f4
                            WHERE f4.fact_type = 'PERF_EXPECT' AND f4.fact_status = 'ACTIVE'
                              AND (CASE WHEN f4.biz_type IN ('一手房','房产金融','家装荐客')
                                        THEN COALESCE(NULLIF(f4.order_no,''), NULLIF(f4.contract_no,''))
                                        ELSE COALESCE(NULLIF(f4.contract_no,''), NULLIF(f4.order_no,'')) END)
                                  = a.biz_key
                              AND f4.employee_external_code IS NOT DISTINCT FROM ci.employee_code) = 1))
        ) arc ON TRUE
        LEFT JOIN LATERAL (
            SELECT SUM(o.orig_pos_amt) AS orig_pos_all,
                   SUM(o.orig_neg_amt) AS orig_neg_all
            FROM original_received o
            WHERE o.biz_key = CASE WHEN ci.biz_type IN ('一手房','房产金融','家装荐客')
                                   THEN COALESCE(NULLIF(ci.order_no,''), NULLIF(ci.contract_no,''))
                                   ELSE COALESCE(NULLIF(ci.contract_no,''), NULLIF(ci.order_no,'')) END
              AND (o.employee_id = ci.employee_id
                   OR (o.emp_code IS NOT NULL AND o.emp_code = ci.employee_code))
              -- 同 arc：角色命名不一致时按人兜底（原值口径必须与调整后口径同配对）
              AND (o.role_type IS NOT DISTINCT FROM ci.role_type
                   OR (NOT EXISTS (
                           SELECT 1 FROM pj_perf_fact f3
                           WHERE f3.fact_type = 'PERF_EXPECT' AND f3.fact_status = 'ACTIVE'
                             AND (CASE WHEN f3.biz_type IN ('一手房','房产金融','家装荐客')
                                       THEN COALESCE(NULLIF(f3.order_no,''), NULLIF(f3.contract_no,''))
                                       ELSE COALESCE(NULLIF(f3.contract_no,''), NULLIF(f3.order_no,'')) END)
                                 = o.biz_key
                             AND f3.employee_external_code IS NOT DISTINCT FROM ci.employee_code
                             AND f3.role_type IS NOT DISTINCT FROM o.role_type)
                       AND (SELECT count(DISTINCT f4.role_type) FROM pj_perf_fact f4
                            WHERE f4.fact_type = 'PERF_EXPECT' AND f4.fact_status = 'ACTIVE'
                              AND (CASE WHEN f4.biz_type IN ('一手房','房产金融','家装荐客')
                                        THEN COALESCE(NULLIF(f4.order_no,''), NULLIF(f4.contract_no,''))
                                        ELSE COALESCE(NULLIF(f4.contract_no,''), NULLIF(f4.order_no,'')) END)
                                  = o.biz_key
                              AND f4.employee_external_code IS NOT DISTINCT FROM ci.employee_code) = 1))
        ) orc ON TRUE
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
            WHERE fact_status = 'ACTIVE' AND fact_type = 'PERF_EXPECT'
              <if test="period != null and period != ''">AND period = #{period}</if>
              AND source = 'MANUAL'
              AND (source_key LIKE '%|MANUAL-ADJ%' OR source_key LIKE '%|MANUAL-CADJ%')
              AND contract_no IN
              <foreach collection="keys" item="bk" open="(" separator="," close=")">#{bk}</foreach>
            UNION
            SELECT order_no AS biz_key FROM pj_perf_fact
            WHERE fact_status = 'ACTIVE' AND fact_type = 'PERF_EXPECT'
              <if test="period != null and period != ''">AND period = #{period}</if>
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
