package com.panjia.commission.mapper;

import com.panjia.commission.domain.CommissionItem;
import com.panjia.commission.dto.CommissionItemDetailDTO;
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
     * 通过 performance_fact_id 关联业绩事实、员工、部门、归一化记录等表，
     * 补充：工号、姓名、门店/组别路径、角色占比、应收金额。
     * DIFF 差额行（performance_fact_id 为空）仅展示结佣明细基础字段。
     * <p>
     * 应收同时给出 originalExpectedAmount（调整前：同 sourceKey 最早一条 REVERSED 的
     * PERF_EXPECT，无则回退当前 ACTIVE 值）与 expectedAdjusted 标记，
     * 与「实收明细详情」同口径，供前端展示「原值 → 调整后值」。
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
        )
        SELECT ci.id AS "itemId",
               ci.performance_fact_id AS "factId",
               ci.employee_id AS "employeeId",
               COALESCE(e.employee_code, f.employee_external_code) AS "employeeCode",
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
               COALESCE(f.role_type, ci.role_type) AS "roleType",
               f.role_name AS "roleName",
               f.share_ratio AS "shareRatio",
               ci.biz_type AS "bizType",
               ae.performance_amount AS "expectedAmount",
               COALESCE(re.performance_amount, ae.performance_amount) AS "originalExpectedAmount",
               (re.source_key IS NOT NULL) AS "expectedAdjusted",
               ci.amount AS "amount",
               ci.fee_item AS "feeItem",
               ci.status AS "status"
        FROM pj_commission_item ci
        LEFT JOIN pj_perf_fact f ON f.id = ci.performance_fact_id
        LEFT JOIN active_expect ae ON ae.source_key = f.source_key
        LEFT JOIN reversed_expect re ON re.source_key = f.source_key
        LEFT JOIN pj_people_employee e ON e.employee_id = ci.employee_id
        LEFT JOIN sys_dept d ON d.dept_id = ci.dept_id
        LEFT JOIN sys_dept p ON p.dept_id = d.parent_id
        LEFT JOIN sys_dept gp ON gp.dept_id = p.parent_id
        WHERE ci.application_id = #{applicationId}
          AND ci.status != 'REVERSED'
        ORDER BY e.employee_name, d.dept_id, f.role_type, ci.id
        </script>
        """)
    List<CommissionItemDetailDTO> selectItemDetails(@Param("applicationId") Long applicationId);

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
