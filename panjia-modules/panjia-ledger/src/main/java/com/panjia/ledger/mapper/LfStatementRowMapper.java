package com.panjia.ledger.mapper;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.panjia.ledger.domain.LfStatementRow;
import com.panjia.ledger.dto.DeptRowDTO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 理房通流水 Mapper。
 * <p>跨域只读：系统实收来自 received 域，新签角色人来自 pj_perf_fact；本 Mapper 只读跨域数据。</p>
 */
@Mapper
public interface LfStatementRowMapper extends BaseMapperPlus<LfStatementRow, LfStatementRow> {

    /** 本次批次涉及的业务合同号 */
    @Select("""
        SELECT DISTINCT contract_no
        FROM pj_lf_statement_row
        WHERE batch_id = #{batchId}
          AND contract_no IS NOT NULL AND contract_no <> ''
          AND category IN ('BUSINESS', 'REVERSAL')
        """)
    List<String> selectContractNosByBatch(@Param("batchId") Long batchId);

    /** 全量业务合同号（定时匹配用） */
    @Select("""
        SELECT DISTINCT contract_no
        FROM pj_lf_statement_row
        WHERE contract_no IS NOT NULL AND contract_no <> ''
          AND category IN ('BUSINESS', 'REVERSAL')
        """)
    List<String> selectAllMatchableContractNos();

    /** 某合同全部流水净额（收入 + 支出，排除提现/费用/非业务） */
    @Select("""
        SELECT COALESCE(SUM(transaction_amount), 0)
        FROM pj_lf_statement_row
        WHERE contract_no = #{contractNo}
          AND category IN ('BUSINESS', 'REVERSAL')
          AND decoration_flag = FALSE
        """)
    BigDecimal sumFlowByContract(@Param("contractNo") String contractNo);

    @Select("""
        SELECT COUNT(*)
        FROM pj_lf_statement_row
        WHERE contract_no = #{contractNo}
          AND category IN ('BUSINESS', 'REVERSAL')
          AND decoration_flag = FALSE
        """)
    int countFlowByContract(@Param("contractNo") String contractNo);

    /** 某合同全部实收净额（所有期间、所有来源） */
    @Select("""
        SELECT COALESCE(SUM(d.performance_amount), 0)
             + COALESCE((
                 SELECT SUM(a.target_amount - a.original_amount)
                 FROM pj_perf_received_adjust a
                 WHERE a.status NOT IN ('EXECUTED', 'REJECTED')
                   AND (a.contract_no = #{contractNo} OR a.order_no = #{contractNo})
             ), 0)
        FROM pj_received_detail d
        JOIN pj_received_contract c ON c.id = d.contract_id
        WHERE d.detail_status = 'ACTIVE'
          AND (c.contract_no = #{contractNo} OR c.order_no = #{contractNo})
        """)
    BigDecimal sumReceivedByContract(@Param("contractNo") String contractNo);

    /** 某合同全部实收明细分行（差异下钻） */
    @Select("""
        SELECT d.id AS "id", d.period AS "period", d.employee_id AS "employeeId",
               d.role_name AS "roleName", d.role_type AS "roleType",
               d.performance_amount AS "performanceAmount", c.source_type AS "sourceType",
               a.status AS "applyStatus"
        FROM pj_received_detail d
        JOIN pj_received_contract c ON c.id = d.contract_id
        LEFT JOIN pj_perf_received_apply a ON a.id = d.received_apply_id
        WHERE d.detail_status = 'ACTIVE'
          AND (c.contract_no = #{contractNo} OR c.order_no = #{contractNo})
        UNION ALL
        SELECT a.id AS "id", a.period AS "period", a.employee_id AS "employeeId",
               '调整单' AS "roleName", 'ADJUST' AS "roleType",
               (a.target_amount - a.original_amount) AS "performanceAmount",
               'RECEIVED_ADJUST' AS "sourceType", a.status AS "applyStatus"
        FROM pj_perf_received_adjust a
        WHERE a.status NOT IN ('EXECUTED', 'REJECTED')
          AND (a.contract_no = #{contractNo} OR a.order_no = #{contractNo})
        ORDER BY "period", "id"
        """)
    List<Map<String, Object>> selectReceivedLines(@Param("contractNo") String contractNo);

    /** 某合同全部流水行（差异下钻） */
    @Select("""
        SELECT id, period, store_name, transaction_time, income_expense_type,
               business_desc, transaction_remark, transaction_amount, voucher_no
        FROM pj_lf_statement_row
        WHERE contract_no = #{contractNo}
          AND category IN ('BUSINESS', 'REVERSAL')
        ORDER BY transaction_time, id
        """)
    List<Map<String, Object>> selectFlowLines(@Param("contractNo") String contractNo);

    /** 更新某合同所有流水行的匹配状态（去化行单独处理） */
    @Update("""
        UPDATE pj_lf_statement_row
        SET match_status = #{status}, match_diff_amount = #{diff}, match_time = now(), update_time = now()
        WHERE contract_no = #{contractNo}
          AND category IN ('BUSINESS', 'REVERSAL')
          AND decoration_flag = FALSE
        """)
    int updateMatchByContract(@Param("contractNo") String contractNo,
                              @Param("status") String status,
                              @Param("diff") BigDecimal diff);

    @Update("""
        UPDATE pj_lf_statement_row
        SET match_status = #{status}, match_time = now(), update_time = now()
        WHERE contract_no = #{contractNo} AND decoration_flag = TRUE
        """)
    int updateDecorationMatch(@Param("contractNo") String contractNo, @Param("status") String status);

    /** 去化合同的新签角色人（PERF_EXPECT ACTIVE，合同全周期） */
    @Select("""
        SELECT employee_id AS "employeeId", MAX(role_type) AS "roleType",
               MAX(role_name) AS "roleName", SUM(performance_amount) AS "performanceAmount",
               MAX(share_ratio) AS "shareRatio", MAX(order_no) AS "orderNo",
               MAX(dept_id) AS "deptId", MAX(property_address) AS "propertyAddress",
               MAX(business_date) AS "businessDate"
        FROM pj_perf_fact
        WHERE fact_type = 'PERF_EXPECT'
          AND fact_status = 'ACTIVE'
          AND biz_type = '去化模式'
          AND (contract_no = #{contractNo} OR order_no = #{contractNo})
        GROUP BY employee_id
        ORDER BY employee_id
        """)
    List<Map<String, Object>> selectDecorationRoles(@Param("contractNo") String contractNo);

    /** 合同新签总金额（全部角色人合计，用于实收合同快照） */
    @Select("""
        SELECT COALESCE(SUM(performance_amount), 0)
        FROM pj_perf_fact
        WHERE fact_type = 'PERF_EXPECT'
          AND fact_status = 'ACTIVE'
          AND biz_type = '去化模式'
          AND (contract_no = #{contractNo} OR order_no = #{contractNo})
        """)
    BigDecimal sumDecorationExpected(@Param("contractNo") String contractNo);

    /** 门店下的全部有效部门（含自身，最多 4 层） */
    @Select("""
        WITH RECURSIVE sub AS (
            SELECT dept_id, parent_id, dept_name, order_num, 1 AS depth
            FROM sys_dept
            WHERE dept_id = #{deptId} AND del_flag = '0' AND status = '0'
            UNION ALL
            SELECT d.dept_id, d.parent_id, d.dept_name, d.order_num, s.depth + 1
            FROM sys_dept d
            JOIN sub s ON d.parent_id = s.dept_id
            WHERE d.del_flag = '0' AND d.status = '0' AND s.depth < 4
        )
        SELECT dept_id AS deptId, parent_id AS parentId, dept_name AS deptName, order_num AS orderNum
        FROM sub ORDER BY depth, order_num, dept_id
        """)
    List<DeptRowDTO> selectDeptSubtree(@Param("deptId") Long deptId);

    /** 登录部门的所有上级部门（含自身），用于店长按本门店过滤 */
    @Select("""
        SELECT dept_id
        FROM sys_dept
        WHERE del_flag = '0'
          AND dept_id IN (
              SELECT UNNEST(STRING_TO_ARRAY(
                  (SELECT ancestors FROM sys_dept WHERE dept_id = #{deptId}), ','
              ))::BIGINT
          )
        """)
    List<Long> selectAncestorDeptIds(@Param("deptId") Long deptId);

    /** 待补科目的费用描述汇总 */
    @Select("""
        SELECT business_desc AS "desc", COUNT(*) AS "cnt"
        FROM pj_lf_statement_row
        WHERE batch_id = #{batchId} AND category = 'FEE' AND subject_id IS NULL
        GROUP BY business_desc
        """)
    List<Map<String, Object>> selectUnmappedSubjects(@Param("batchId") Long batchId);

    /** 待补科目汇总（按费用描述） */
    @Select("""
        SELECT business_desc AS "desc", COUNT(*) AS "cnt",
               COALESCE(SUM(ABS(transaction_amount)), 0) AS "amount"
        FROM pj_lf_statement_row
        WHERE category = 'FEE' AND subject_id IS NULL
          AND (COALESCE(#{storeDeptId,jdbcType=BIGINT}, 0) = 0 OR store_dept_id = #{storeDeptId,jdbcType=BIGINT})
        GROUP BY business_desc
        ORDER BY COUNT(*) DESC
        """)
    Page<Map<String, Object>> selectPendingSubjectSummary(Page<Map<String, Object>> page,
                                                          @Param("storeDeptId") Long storeDeptId);

    /** 某批次单组自动划归的费用行（用于写 pj_ledger_entry） */
    @Select("""
        SELECT *
        FROM pj_lf_statement_row
        WHERE batch_id = #{batchId}
          AND category = 'FEE'
          AND subject_id IS NOT NULL
          AND expense_dept_id IS NOT NULL
        """)
    List<LfStatementRow> selectExpenseRowsForLedger(@Param("batchId") Long batchId);

    /** 指定 ID 的费用行（手工划归后同步） */
    @Select("""
        <script>
        SELECT * FROM pj_lf_statement_row
        WHERE category = 'FEE' AND id IN
        <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
        </script>
        """)
    List<LfStatementRow> selectExpenseRowsByIds(@Param("ids") List<Long> ids);
}
