package com.panjia.performance.mapper;

import com.panjia.performance.domain.PerformanceFact;
import com.panjia.performance.dto.PerformanceManageDTO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;

import java.util.List;

/**
 * 业绩事实 Mapper。
 */
@Mapper
public interface PerformanceFactMapper extends BaseMapperPlus<PerformanceFact, PerformanceFact> {

    /**
     * 业绩明细列表（人 → 合同 → 明细 树表的明细层数据源）。
     * <p>
     * 字段与 KE《经纪人业绩结算明细表》导入行一一对应：
     * <ul>
     *   <li>签约/认购日期 ← raw_json.signDate（实际签约时间；缺失回退事实归属月初）</li>
     *   <li>合同号 ← raw_signed.contract_no；类型 ← fact.biz_type；房源地址 ← raw_json.propertyAddress</li>
     *   <li>签约人 ← 员工主数据姓名；店组 ← raw_json.deptName；门店 ← raw_json.storeName（均按导入原值）</li>
     *   <li>所属角色 ← normalized_record.role_type；角色占比 ← fact.share_ratio</li>
     *   <li>金额：PERF_EXPECT=当月应收，PERF_REAL=当月实收</li>
     *   <li>是否结算/结算日期 ← 结佣明细+结佣申请锁定状态</li>
     * </ul>
     * 仅查 ACTIVE 事实。
     *
     * @param period    归属期间（必填）
     * @param factType  事实口径（必填：PERF_REAL / PERF_EXPECT）
     * @param deptId    部门 ID（可选，含子部门，按员工归属部门过滤）
     * @param bizType   业务类型（可选）
     * @param settled   是否已结算（可选；null=全部，true=已结算，false=未结算）
     * @return 业绩明细行
     */
    @Select("""
        <script>
        SELECT f.id,
               f.fact_type AS factType,
               f.period,
               COALESCE((rs.raw_json ->> 'signDate')::timestamp::date, f.business_date) AS businessDate,
               rs.order_no AS orderNo,
               rs.contract_no AS contractNo,
               f.biz_type AS bizType,
               rs.raw_json ->> 'propertyAddress' AS propertyAddress,
               f.employee_id AS employeeId,
               e.employee_name AS employeeName,
               e.employee_code AS employeeCode,
               COALESCE(rs.raw_json ->> 'storeName',
                   CASE
                       WHEN array_length(string_to_array(d.ancestors, ','), 1) = 2 THEN d.dept_name
                       WHEN array_length(string_to_array(d.ancestors, ','), 1) >= 3 THEN p.dept_name
                       ELSE d.dept_name
                   END) AS storeName,
               COALESCE(rs.raw_json ->> 'deptName',
                   CASE
                       WHEN array_length(string_to_array(d.ancestors, ','), 1) >= 3 THEN d.dept_name
                       ELSE NULL
                   END) AS groupName,
               COALESCE(nr.role_type, f.role_type) AS roleType,
               rs.role_name AS roleName,
               f.share_ratio AS shareRatio,
               f.origin_amount AS amount,
               (ci.id IS NOT NULL) AS settled,
               ca.lock_time AS settleDate,
               f.source_key AS sourceKey
        FROM pj_perf_fact f
        LEFT JOIN pj_people_employee e ON e.employee_id = f.employee_id
        LEFT JOIN sys_dept d ON d.dept_id = f.dept_id
        LEFT JOIN sys_dept p ON p.dept_id = d.parent_id
        LEFT JOIN pj_normalized_record nr ON nr.id = f.normalized_record_id
        LEFT JOIN pj_import_raw_signed rs ON rs.id = nr.raw_data_id
        LEFT JOIN pj_commission_item ci ON ci.performance_fact_id = f.id
                                       AND ci.status &lt;&gt; 'REVERSED'
        LEFT JOIN pj_commission_application ca ON ca.id = ci.application_id
                                              AND ca.status IN ('APPROVED', 'LOCKED', 'CLOSED')
        WHERE f.fact_status = 'ACTIVE'
          AND f.period = #{period}
          AND f.fact_type = #{factType}
          <if test="deptId != null">
            AND (f.dept_id = #{deptId}
                 OR EXISTS (SELECT 1 FROM sys_dept sd WHERE sd.dept_id = f.dept_id
                            AND sd.ancestors LIKE CONCAT('%', #{deptId}, '%')))
          </if>
          <if test="bizType != null and bizType != ''">
            AND f.biz_type = #{bizType}
          </if>
          <if test="settled != null">
            <choose>
                <when test="settled">
                  AND ci.id IS NOT NULL
                </when>
                <otherwise>
                  AND ci.id IS NULL
                </otherwise>
            </choose>
          </if>
        ORDER BY e.employee_name, rs.contract_no, businessDate, nr.role_type
        </script>
        """)
    List<PerformanceManageDTO> selectManageList(@Param("period") String period,
                                                @Param("factType") String factType,
                                                @Param("deptId") Long deptId,
                                                @Param("bizType") String bizType,
                                                @Param("settled") Boolean settled);

    /**
     * 查询有 ACTIVE 业绩事实的期间（最近在前），供前端默认选中最新数据期间。
     *
     * @return 期间列表（YYYY-MM，倒序）
     */
    @Select("""
        SELECT period FROM pj_perf_fact
        WHERE fact_status = 'ACTIVE' AND period IS NOT NULL
        GROUP BY period
        ORDER BY period DESC
        """)
    List<String> selectManagePeriods();
}
