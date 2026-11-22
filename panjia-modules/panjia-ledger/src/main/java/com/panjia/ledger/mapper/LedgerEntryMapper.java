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
 * <p>跨域只读查询说明：实收明细来自 received 域（pj_received_*），工资应发来自 payroll 域
 * （pj_payroll_*），部门来自 people 域（sys_dept）。此处仅做报表取数，不写跨域数据。</p>
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
     * <p>与结佣审批筛选「已锁定」的实收合计一致：同业务键、同方向按到账月归属到对应结佣单，
     * 早于全部单时归最早单，晚于全部单时归最晚单；补收月剔除，负向实收归负向结佣单。
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
            SELECT s.dept_id, s.negative_amount AS amount, na.id AS app_id
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
            WHERE s.negative_amount IS NOT NULL
        )
        SELECT a.dept_id AS deptId, SUM(a.amount) AS amount
        FROM assigned a
        JOIN selected_apps sa ON sa.id = a.app_id
        GROUP BY a.dept_id
        """)
    List<DeptAmountDTO> sumReceivedByDept(@Param("period") String period, @Param("refDate") LocalDate refDate);

    /**
     * 工资应发（gross）按部门汇总（支出）；仅统计已计算及之后状态的批次。
     * <p>部门优先取工资明细快照，其次按归属月取 DEPT 事实（员工调店时间轴）。</p>
     */
    @Select("""
        SELECT COALESCE(d.dept_id, df.value::BIGINT) AS deptId,
               SUM(COALESCE(d.gross, 0)) AS amount
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
        GROUP BY COALESCE(d.dept_id, df.value::BIGINT)
        """)
    List<DeptAmountDTO> sumPayrollGrossByDept(@Param("period") String period, @Param("refDate") LocalDate refDate);

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
