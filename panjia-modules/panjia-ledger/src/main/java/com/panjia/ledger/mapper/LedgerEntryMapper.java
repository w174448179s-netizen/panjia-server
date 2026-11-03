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
     * 实收明细按部门汇总（收入）。
     * <p>有效口径：明细 ACTIVE + 实收申请 APPROVED；部门优先取明细/合同快照，
     * 其次按归属月取 DEPT 事实（员工调店时间轴），最后回退员工当前部门；
     * 退款/冲减行保留负金额参与净额。</p>
     */
    @Select("""
        SELECT COALESCE(d.dept_id, c.dept_id, df.value::BIGINT, e.dept_id) AS deptId,
               SUM(COALESCE(d.performance_amount, 0)) AS amount
        FROM pj_received_detail d
        JOIN pj_received_contract c ON c.id = d.contract_id
        JOIN pj_perf_received_apply a ON a.id = c.received_apply_id
        LEFT JOIN pj_people_employee e ON e.employee_id = d.employee_id
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
          AND a.status = 'APPROVED'
          AND d.period = #{period}
        GROUP BY COALESCE(d.dept_id, c.dept_id, df.value::BIGINT, e.dept_id)
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
