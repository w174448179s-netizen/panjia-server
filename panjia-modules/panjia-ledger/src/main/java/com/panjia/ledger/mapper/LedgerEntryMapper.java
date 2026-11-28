package com.panjia.ledger.mapper;

import com.panjia.ledger.domain.LedgerEntry;
import com.panjia.ledger.dto.DeptAmountDTO;
import com.panjia.ledger.dto.DeptRowDTO;
import com.panjia.ledger.dto.SubjectDeptAmountDTO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;

import java.time.LocalDate;
import java.util.List;

/**
 * 部门收支录入 Mapper。
 * <p>跨域只读查询说明：实收明细来自 received 域（pj_received_*），工资应发与门店社保、
 * 商业保险配置来自 payroll 域（pj_payroll_*），部门及人员来自 people 域（sys_dept、pj_people_*）。
 * 此处仅做报表取数，不写跨域数据。</p>
 */
@Mapper
public interface LedgerEntryMapper extends BaseMapperPlus<LedgerEntry, LedgerEntry> {

    /** 有效部门（报表列与末级校验） */
    @Select("""
        SELECT dept_id AS deptId, parent_id AS parentId, dept_name AS deptName, order_num AS orderNum
        FROM sys_dept
        WHERE del_flag = '0' AND status = '0'
        ORDER BY parent_id, order_num, dept_id
        """)
    List<DeptRowDTO> selectActiveDepts();

    /**
     * 所选月份已锁定结佣单的有效实收按部门汇总（收入）。
     * <p>与结佣审批筛选「已锁定」的实收（含负向冲减）一致：同业务键、同方向按到账月归属到对应结佣单，
     * 早于全部单时归最早单，晚于全部单时归最晚单；补收月剔除。
     * 负向实收优先归负向单，无负向单时回挂正向单冲减实收。
     * 部门优先取明细/合同快照，其次取 DEPT 事实，最后回退员工当前部门。</p>
     */
    @Select("""
        WITH selected_apps AS (
            SELECT ca.id, ca.contract_no, ca.order_no
            FROM pj_commission_application ca
            WHERE ca.period = #{period}
              AND ca.status = 'LOCKED'
              AND EXISTS (
                  SELECT 1 FROM pj_commission_item ci
                  WHERE ci.application_id = ca.id AND ci.status = 'APPROVED'
              )
        ), selected_keys AS (
            SELECT ci.contract_no AS k
            FROM selected_apps sa
            JOIN pj_commission_item ci ON ci.application_id = sa.id AND ci.status = 'APPROVED'
            WHERE NULLIF(BTRIM(ci.contract_no), '') IS NOT NULL
            UNION
            SELECT ci.order_no AS k
            FROM selected_apps sa
            JOIN pj_commission_item ci ON ci.application_id = sa.id AND ci.status = 'APPROVED'
            WHERE NULLIF(BTRIM(ci.order_no), '') IS NOT NULL
        ), received AS (
            SELECT CASE WHEN c.biz_type IN ('一手房','房产金融','家装荐客')
                        THEN COALESCE(NULLIF(c.order_no, ''), NULLIF(c.contract_no, ''))
                        ELSE COALESCE(NULLIF(c.contract_no, ''), NULLIF(c.order_no, '')) END AS biz_key,
                   d.period,
                   COALESCE(d.dept_id, c.dept_id, df.value::BIGINT, e.dept_id) AS dept_id,
                   d.performance_amount AS amount
            FROM pj_received_detail d
            JOIN pj_received_contract c ON c.id = d.contract_id
            LEFT JOIN pj_perf_received_apply ra ON ra.id = d.received_apply_id
            LEFT JOIN pj_people_employee e
              ON e.employee_id = d.employee_id
              OR (d.employee_id IS NULL AND e.employee_code = d.employee_external_code)
            LEFT JOIN LATERAL (
                SELECT f.value
                FROM pj_people_salary_fact f
                WHERE f.employee_id = d.employee_id
                  AND f.fact_type = 'DEPT'
                  AND f.effective_date <= #{refDate}
                  AND (f.expire_date IS NULL OR #{refDate} < f.expire_date)
                ORDER BY f.effective_date DESC, f.create_time DESC
                LIMIT 1
            ) df ON TRUE
            WHERE d.detail_status = 'ACTIVE'
              AND (d.received_apply_id IS NULL OR ra.status = 'APPROVED')
              AND EXISTS (
                  SELECT 1 FROM selected_keys k
                  WHERE c.contract_no = k.k OR c.order_no = k.k
              )
        ), received_stats AS (
            SELECT biz_key, period, dept_id,
                   SUM(amount) FILTER (WHERE amount > 0) AS positive_amount,
                   SUM(amount) FILTER (WHERE amount < 0) AS negative_amount
            FROM received
            WHERE biz_key IS NOT NULL AND period IS NOT NULL
            GROUP BY biz_key, period, dept_id
        ), expect_signs AS (
            SELECT CASE WHEN pe.biz_type IN ('一手房','房产金融','家装荐客')
                        THEN COALESCE(NULLIF(pe.order_no, ''), NULLIF(pe.contract_no, ''))
                        ELSE COALESCE(NULLIF(pe.contract_no, ''), NULLIF(pe.order_no, '')) END AS biz_key,
                   pe.period,
                   BOOL_OR(pe.performance_amount > 0) AS has_positive,
                   BOOL_OR(pe.performance_amount < 0) AS has_negative
            FROM pj_perf_fact pe
            WHERE pe.fact_type = 'PERF_EXPECT'
              AND pe.fact_status = 'ACTIVE'
              AND EXISTS (
                  SELECT 1 FROM selected_keys k
                  WHERE pe.contract_no = k.k OR pe.order_no = k.k
              )
            GROUP BY 1, 2
        ), attribution_stats AS (
            SELECT rs.*
            FROM received_stats rs
            WHERE rs.positive_amount IS NULL
               OR EXISTS (
                   SELECT 1 FROM expect_signs es
                   WHERE es.biz_key = rs.biz_key AND es.period = rs.period AND es.has_positive
               )
               OR NOT EXISTS (
                   SELECT 1 FROM expect_signs es
                   WHERE es.biz_key = rs.biz_key AND es.period < rs.period AND es.has_negative
               )
        ), assigned AS (
            SELECT s.dept_id, s.positive_amount AS amount, pa.id AS app_id
            FROM attribution_stats s
            LEFT JOIN LATERAL (
                SELECT x.id
                FROM (
                    (SELECT ca.id, 1 AS priority
                     FROM pj_commission_application ca
                     WHERE (ca.contract_no = s.biz_key OR ca.order_no = s.biz_key)
                       AND ca.total_amount IS NOT NULL AND ca.total_amount >= 0
                       AND ca.status NOT IN ('REJECTED', 'CANCELLED')
                       AND COALESCE(ca.period, '') <= s.period
                     ORDER BY ca.period DESC, ca.id DESC LIMIT 1)
                    UNION ALL
                    (SELECT ca.id, 2 AS priority
                     FROM pj_commission_application ca
                     WHERE (ca.contract_no = s.biz_key OR ca.order_no = s.biz_key)
                       AND ca.total_amount IS NOT NULL AND ca.total_amount >= 0
                       AND ca.status NOT IN ('REJECTED', 'CANCELLED')
                     ORDER BY ca.period ASC, ca.id ASC LIMIT 1)
                ) x
                ORDER BY x.priority
                LIMIT 1
            ) pa ON TRUE
            WHERE s.positive_amount IS NOT NULL
            UNION ALL
            SELECT s.dept_id, s.negative_amount AS amount, COALESCE(na.id, pa.id) AS app_id
            FROM attribution_stats s
            LEFT JOIN LATERAL (
                SELECT x.id
                FROM (
                    (SELECT ca.id, 1 AS priority
                     FROM pj_commission_application ca
                     WHERE (ca.contract_no = s.biz_key OR ca.order_no = s.biz_key)
                       AND ca.total_amount IS NOT NULL AND ca.total_amount < 0
                       AND ca.status NOT IN ('REJECTED', 'CANCELLED')
                       AND COALESCE(ca.period, '') <= s.period
                     ORDER BY ca.period DESC, ca.id DESC LIMIT 1)
                    UNION ALL
                    (SELECT ca.id, 2 AS priority
                     FROM pj_commission_application ca
                     WHERE (ca.contract_no = s.biz_key OR ca.order_no = s.biz_key)
                       AND ca.total_amount IS NOT NULL AND ca.total_amount < 0
                       AND ca.status NOT IN ('REJECTED', 'CANCELLED')
                     ORDER BY ca.period ASC, ca.id ASC LIMIT 1)
                ) x
                ORDER BY x.priority
                LIMIT 1
            ) na ON TRUE
            LEFT JOIN LATERAL (
                SELECT x.id
                FROM (
                    (SELECT ca.id, 1 AS priority
                     FROM pj_commission_application ca
                     WHERE (ca.contract_no = s.biz_key OR ca.order_no = s.biz_key)
                       AND ca.total_amount IS NOT NULL AND ca.total_amount >= 0
                       AND ca.status NOT IN ('REJECTED', 'CANCELLED')
                       AND COALESCE(ca.period, '') <= s.period
                     ORDER BY ca.period DESC, ca.id DESC LIMIT 1)
                    UNION ALL
                    (SELECT ca.id, 2 AS priority
                     FROM pj_commission_application ca
                     WHERE (ca.contract_no = s.biz_key OR ca.order_no = s.biz_key)
                       AND ca.total_amount IS NOT NULL AND ca.total_amount >= 0
                       AND ca.status NOT IN ('REJECTED', 'CANCELLED')
                     ORDER BY ca.period ASC, ca.id ASC LIMIT 1)
                ) x
                ORDER BY x.priority
                LIMIT 1
            ) pa ON TRUE
            WHERE s.negative_amount IS NOT NULL
        )
        SELECT a.dept_id AS deptId, SUM(a.amount) AS amount
        FROM assigned a
        JOIN selected_apps sa ON sa.id = a.app_id
        GROUP BY a.dept_id
        """)
    List<DeptAmountDTO> sumReceivedByDept(@Param("period") String period, @Param("refDate") LocalDate refDate);

    /**
     * 工资按部门汇总（支出）；仅统计已计算及之后状态的批次。
     * <p>口径：普通员工（非总监）取应发大于 0 的 gross 合计；总监工资按管辖店组分摊——
     * 每个店组计入「底薪 ÷ 店组数量 + 该店组的总监提成」，不再把总监整笔工资挂在其所属非末级部门。</p>
     * <p>部门优先取工资明细快照，其次按归属月取 DEPT 事实（员工调店时间轴）。</p>
     */
    @Select("""
        WITH payroll_rows AS (
            SELECT COALESCE(d.employee_role, '') AS employee_role,
                   COALESCE(d.gross, 0) AS gross,
                   COALESCE(NULLIF(d.base_salary, 0), 6000) AS base_salary,
                   d.director_store_items,
                   COALESCE(d.dept_id, df.value::BIGINT) AS dept_id
            FROM pj_payroll_detail d
            JOIN pj_payroll_batch b ON b.id = d.batch_id
            LEFT JOIN LATERAL (
                SELECT f.value
                FROM pj_people_salary_fact f
                WHERE f.employee_id = d.employee_id
                  AND f.fact_type = 'DEPT'
                  AND f.effective_date <= #{refDate}
                  AND (f.expire_date IS NULL OR #{refDate} < f.expire_date)
                ORDER BY f.effective_date DESC, f.create_time DESC
                LIMIT 1
            ) df ON TRUE
            WHERE d.period = #{period}
              AND b.status IN ('已计算', '待审核', '已确认', '已锁定', '已发放')
        ), staff_salary AS (
            SELECT dept_id, SUM(gross) AS amount
            FROM payroll_rows
            WHERE employee_role <> 'DIRECTOR'
              AND gross > 0
            GROUP BY dept_id
        ), director_items AS (
            SELECT NULLIF(it->>'deptId', '')::BIGINT AS dept_id,
                   COALESCE(NULLIF(it->>'income', '')::NUMERIC, 0) AS income,
                   base_salary,
                   jsonb_array_length(director_store_items::jsonb) AS group_count
            FROM payroll_rows
            CROSS JOIN LATERAL jsonb_array_elements(director_store_items::jsonb) it
            WHERE employee_role = 'DIRECTOR'
              AND director_store_items LIKE '[%'
              AND jsonb_array_length(director_store_items::jsonb) > 0
        ), director_salary AS (
            SELECT dept_id, SUM(income + base_salary / group_count) AS amount
            FROM director_items
            WHERE dept_id IS NOT NULL
            GROUP BY dept_id
        )
        SELECT dept_id AS deptId, SUM(amount) AS amount
        FROM (
            SELECT dept_id, amount FROM staff_salary
            UNION ALL
            SELECT dept_id, amount FROM director_salary
        ) t
        GROUP BY dept_id
        """)
    List<DeptAmountDTO> sumPayrollGrossByDept(@Param("period") String period, @Param("refDate") LocalDate refDate);

    /**
     * 社保费用按部门汇总（支出）：薪酬配置 DEPT 政策 socialStandard × 该部门已配置缴社保的在职/兼职人数；
     * 另加总监社保分摊——总监缴纳的社保（公司承担部分）÷ 管辖店组数，逐店组计入。
     * <p>人数仅统计当日最新 SOCIAL 事实为 true 的员工（非全员）；员工部门优先取归属月首日生效的 DEPT 事实，缺失时回退员工当前部门。</p>
     */
    @Select("""
        WITH employee_dept AS (
            SELECT e.employee_id, COALESCE(df.value::BIGINT, e.dept_id) AS dept_id
            FROM pj_people_employee e
            LEFT JOIN LATERAL (
                SELECT f.value
                FROM pj_people_salary_fact f
                WHERE f.employee_id = e.employee_id
                  AND f.fact_type = 'DEPT'
                  AND f.effective_date <= #{refDate}
                  AND (f.expire_date IS NULL OR #{refDate} < f.expire_date)
                ORDER BY f.effective_date DESC, f.create_time DESC
                LIMIT 1
            ) df ON TRUE
            LEFT JOIN LATERAL (
                SELECT f.value
                FROM pj_people_salary_fact f
                WHERE f.employee_id = e.employee_id
                  AND f.fact_type = 'SOCIAL'
                  AND f.effective_date <= #{refDate}
                  AND (f.expire_date IS NULL OR #{refDate} < f.expire_date)
                ORDER BY f.effective_date DESC, f.create_time DESC
                LIMIT 1
            ) sf ON TRUE
            WHERE e.status IN ('ACTIVE', 'PARTTIME')
              AND sf.value = 'true'
        ), dept_policy AS (
            SELECT p.scope_key::BIGINT AS dept_id,
                   NULLIF(BTRIM(p.rule_content->>'socialStandard'), '')::NUMERIC AS standard,
                   ROW_NUMBER() OVER (
                       PARTITION BY p.scope_key
                       ORDER BY p.effective_from DESC, p.id DESC
                   ) AS rn
            FROM pj_payroll_policy_rule p
            WHERE p.scope_type = 'DEPT'
              AND p.scope_key IS NOT NULL
              AND p.effective_from <= #{refDate}
              AND (p.effective_to IS NULL OR p.effective_to >= #{refDate})
        ), staff_social AS (
            SELECT ed.dept_id AS dept_id, dp.standard * COUNT(*) AS amount
            FROM employee_dept ed
            JOIN dept_policy dp ON dp.dept_id = ed.dept_id AND dp.rn = 1
            WHERE ed.dept_id IS NOT NULL
              AND dp.standard IS NOT NULL
            GROUP BY ed.dept_id, dp.standard
        ), director_rows AS (
            SELECT d.employer_social,
                   d.director_store_items::jsonb AS items,
                   jsonb_array_length(d.director_store_items::jsonb) AS group_count
            FROM pj_payroll_detail d
            JOIN pj_payroll_batch b ON b.id = d.batch_id
            WHERE d.period = to_char(#{refDate}, 'YYYY-MM')
              AND b.status IN ('已计算', '待审核', '已确认', '已锁定', '已发放')
              AND d.employee_role = 'DIRECTOR'
              AND d.director_store_items LIKE '[%'
              AND jsonb_array_length(d.director_store_items::jsonb) > 0
              AND COALESCE(d.employer_social, 0) <> 0
        ), director_social AS (
            SELECT NULLIF(it->>'deptId', '')::BIGINT AS dept_id,
                   SUM(dr.employer_social / dr.group_count) AS amount
            FROM director_rows dr
            CROSS JOIN LATERAL jsonb_array_elements(dr.items) it
            WHERE NULLIF(it->>'deptId', '') IS NOT NULL
            GROUP BY NULLIF(it->>'deptId', '')::BIGINT
        )
        SELECT dept_id AS deptId, SUM(amount) AS amount
        FROM (
            SELECT dept_id, amount FROM staff_social
            UNION ALL
            SELECT dept_id, amount FROM director_social
        ) t
        GROUP BY dept_id
        """)
    List<DeptAmountDTO> sumSocialByDept(@Param("refDate") LocalDate refDate);

    /**
     * 商业保险按部门汇总（支出）：归属月首日有效的门店 commercialStandard × 当月已交纳人数。
     * <p>人数仅统计在职及兼职员工，且当日最新 COMMERCIAL 事实为 true；部门归属口径与社保一致。</p>
     */
    @Select("""
        WITH employee_dept AS (
            SELECT e.employee_id, COALESCE(df.value::BIGINT, e.dept_id) AS dept_id
            FROM pj_people_employee e
            LEFT JOIN LATERAL (
                SELECT f.value
                FROM pj_people_salary_fact f
                WHERE f.employee_id = e.employee_id
                  AND f.fact_type = 'DEPT'
                  AND f.effective_date <= #{refDate}
                  AND (f.expire_date IS NULL OR #{refDate} < f.expire_date)
                ORDER BY f.effective_date DESC, f.create_time DESC
                LIMIT 1
            ) df ON TRUE
            LEFT JOIN LATERAL (
                SELECT f.value
                FROM pj_people_salary_fact f
                WHERE f.employee_id = e.employee_id
                  AND f.fact_type = 'COMMERCIAL'
                  AND f.effective_date <= #{refDate}
                  AND (f.expire_date IS NULL OR #{refDate} < f.expire_date)
                ORDER BY f.effective_date DESC, f.create_time DESC
                LIMIT 1
            ) cf ON TRUE
            WHERE e.status IN ('ACTIVE', 'PARTTIME')
              AND cf.value = 'true'
        ), dept_policy AS (
            SELECT p.scope_key::BIGINT AS dept_id,
                   NULLIF(BTRIM(p.rule_content->>'commercialStandard'), '')::NUMERIC AS standard,
                   ROW_NUMBER() OVER (
                       PARTITION BY p.scope_key
                       ORDER BY p.effective_from DESC, p.id DESC
                   ) AS rn
            FROM pj_payroll_policy_rule p
            WHERE p.scope_type = 'DEPT'
              AND p.scope_key IS NOT NULL
              AND p.effective_from <= #{refDate}
              AND (p.effective_to IS NULL OR p.effective_to >= #{refDate})
        )
        SELECT ed.dept_id AS deptId, dp.standard * COUNT(*) AS amount
        FROM employee_dept ed
        JOIN dept_policy dp ON dp.dept_id = ed.dept_id AND dp.rn = 1
        WHERE ed.dept_id IS NOT NULL
          AND dp.standard IS NOT NULL
        GROUP BY ed.dept_id, dp.standard
        """)
    List<DeptAmountDTO> sumCommercialByDept(@Param("refDate") LocalDate refDate);

    /**
     * 宿舍费按部门汇总（支出）：对 DORMITORY=true 的在职/兼职员工逐人取宿舍费后求和。
     * <p>单人金额 = 员工自定义宿舍费（DORMITORY_FEE，含 0）→ 门店宿舍费标准 dormStandard → 全局宿舍管理费 dormitoryFee。</p>
     * <p>部门归属口径与社保一致（DEPT 事实优先，缺失回退员工当前部门）。</p>
     */
    @Select("""
        WITH employee_dept AS (
            SELECT e.employee_id, COALESCE(df.value::BIGINT, e.dept_id) AS dept_id,
                   NULLIF(BTRIM(dormFee.value), '')::NUMERIC AS dorm_fee
            FROM pj_people_employee e
            LEFT JOIN LATERAL (
                SELECT f.value
                FROM pj_people_salary_fact f
                WHERE f.employee_id = e.employee_id
                  AND f.fact_type = 'DEPT'
                  AND f.effective_date <= #{refDate}
                  AND (f.expire_date IS NULL OR #{refDate} < f.expire_date)
                ORDER BY f.effective_date DESC, f.create_time DESC
                LIMIT 1
            ) df ON TRUE
            LEFT JOIN LATERAL (
                SELECT f.value
                FROM pj_people_salary_fact f
                WHERE f.employee_id = e.employee_id
                  AND f.fact_type = 'DORMITORY'
                  AND f.effective_date <= #{refDate}
                  AND (f.expire_date IS NULL OR #{refDate} < f.expire_date)
                ORDER BY f.effective_date DESC, f.create_time DESC
                LIMIT 1
            ) dorm ON TRUE
            LEFT JOIN LATERAL (
                SELECT f.value
                FROM pj_people_salary_fact f
                WHERE f.employee_id = e.employee_id
                  AND f.fact_type = 'DORMITORY_FEE'
                  AND f.effective_date <= #{refDate}
                  AND (f.expire_date IS NULL OR #{refDate} < f.expire_date)
                ORDER BY f.effective_date DESC, f.create_time DESC
                LIMIT 1
            ) dormFee ON TRUE
            WHERE e.status IN ('ACTIVE', 'PARTTIME')
              AND dorm.value = 'true'
        ), dept_policy AS (
            SELECT p.scope_key::BIGINT AS dept_id,
                   NULLIF(BTRIM(p.rule_content->>'dormStandard'), '')::NUMERIC AS standard,
                   ROW_NUMBER() OVER (
                       PARTITION BY p.scope_key
                       ORDER BY p.effective_from DESC, p.id DESC
                   ) AS rn
            FROM pj_payroll_policy_rule p
            WHERE p.scope_type = 'DEPT'
              AND p.scope_key IS NOT NULL
              AND p.effective_from <= #{refDate}
              AND (p.effective_to IS NULL OR p.effective_to >= #{refDate})
        ), global_policy AS (
            SELECT NULLIF(BTRIM(p.rule_content->>'dormitoryFee'), '')::NUMERIC AS fee
            FROM pj_payroll_policy_rule p
            WHERE p.scope_type = 'GLOBAL'
            ORDER BY p.id DESC
            LIMIT 1
        )
        SELECT ed.dept_id AS deptId,
               SUM(COALESCE(ed.dorm_fee, dp.standard, (SELECT fee FROM global_policy), 0)) AS amount
        FROM employee_dept ed
        LEFT JOIN dept_policy dp ON dp.dept_id = ed.dept_id AND dp.rn = 1
        WHERE ed.dept_id IS NOT NULL
        GROUP BY ed.dept_id
        """)
    List<DeptAmountDTO> sumDormitoryByDept(@Param("refDate") LocalDate refDate);

    /**
     * 手工录入按科目+部门汇总；生效期间与归属月相交即整笔计入（不按天折算）。
     */
    @Select("""
        SELECT e.subject_id AS subjectId, e.dept_id AS deptId, SUM(e.amount) AS amount
        FROM pj_ledger_entry e
        WHERE e.start_date <= #{periodEnd}
          AND e.end_date >= #{periodStart}
        GROUP BY e.subject_id, e.dept_id
        """)
    List<SubjectDeptAmountDTO> sumEntriesByPeriod(@Param("periodStart") LocalDate periodStart,
                                                  @Param("periodEnd") LocalDate periodEnd);
}
