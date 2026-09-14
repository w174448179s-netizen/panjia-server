package com.panjia.commission.mapper;

import com.panjia.commission.domain.CommissionItem;
import com.panjia.commission.dto.CommissionItemDetailDTO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;

import java.util.List;

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
     * 通过 performance_fact_id 关联业绩事实、员工、部门、归一化记录等表，
     * 补充：工号、姓名、门店/组别路径、角色占比、应收金额。
     * DIFF 差额行（performance_fact_id 为空）仅展示结佣明细基础字段。
     *
     * @param applicationId 申请单 ID
     * @return 明细详情列表
     */
    @Select("""
        <script>
        SELECT ci.id AS "itemId",
               ci.performance_fact_id AS "factId",
               ci.employee_id AS "employeeId",
               COALESCE(e.employee_code, f.employee_external_code) AS "employeeCode",
               COALESCE(e.employee_name, ci.employee_name) AS "employeeName",
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
               COALESCE(nr.role_type, f.role_type, ci.role_type) AS "roleType",
               rs.role_name AS "roleName",
               f.share_ratio AS "shareRatio",
               (SELECT pe.performance_amount
                  FROM pj_perf_fact pe
                 WHERE pe.fact_status = 'ACTIVE'
                   AND pe.fact_type = 'PERF_EXPECT'
                   AND pe.source_key = f.source_key
                 ORDER BY pe.id
                 LIMIT 1) AS "expectedAmount",
               ci.amount AS "amount",
               ci.fee_item AS "feeItem",
               ci.status AS "status"
        FROM pj_commission_item ci
        LEFT JOIN pj_perf_fact f ON f.id = ci.performance_fact_id
        LEFT JOIN pj_people_employee e ON e.employee_id = ci.employee_id
        LEFT JOIN sys_dept d ON d.dept_id = ci.dept_id
        LEFT JOIN sys_dept p ON p.dept_id = d.parent_id
        LEFT JOIN sys_dept gp ON gp.dept_id = p.parent_id
        LEFT JOIN pj_normalized_record nr ON nr.id = f.normalized_record_id
        LEFT JOIN pj_import_raw_signed rs ON rs.id = nr.raw_data_id
        WHERE ci.application_id = #{applicationId}
          AND ci.status != 'REVERSED'
        ORDER BY e.employee_name, d.dept_id, nr.role_type, ci.id
        </script>
        """)
    List<CommissionItemDetailDTO> selectItemDetails(@Param("applicationId") Long applicationId);
}
