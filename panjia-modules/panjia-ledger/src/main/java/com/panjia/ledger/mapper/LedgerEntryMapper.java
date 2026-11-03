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
     * <p>有效口径：明细 ACTIVE + 实收申请 APPROVED；明细部门为空时回退员工所属部门；
     * 退款/冲减行保留负金额参与净额。</p>
     */
    @Select("""
        SELECT COALESCE(d.dept_id, e.dept_id) AS deptId,
               SUM(COALESCE(d.performance_amount, 0)) AS amount
        FROM pj_received_detail d
        JOIN pj_received_contract c ON c.id = d.contract_id
        JOIN pj_perf_received_apply a ON a.id = c.received_apply_id
        LEFT JOIN pj_people_employee e ON e.employee_id = d.employee_id
        WHERE d.detail_status = 'ACTIVE'
          AND a.status = 'APPROVED'
          AND d.period = #{period}
        GROUP BY COALESCE(d.dept_id, e.dept_id)
        """)
    List<DeptAmountDTO> sumReceivedByDept(@Param("period") String period);

    /** 工资应发（gross）按部门汇总（支出）；仅统计已计算及之后状态的批次 */
    @Select("""
        SELECT d.dept_id AS deptId, SUM(COALESCE(d.gross, 0)) AS amount
        FROM pj_payroll_detail d
        JOIN pj_payroll_batch b ON b.id = d.batch_id
        WHERE d.period = #{period}
          AND b.status IN ('已计算', '待审核', '已确认', '已锁定', '已发放')
        GROUP BY d.dept_id
        """)
    List<DeptAmountDTO> sumPayrollGrossByDept(@Param("period") String period);

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
