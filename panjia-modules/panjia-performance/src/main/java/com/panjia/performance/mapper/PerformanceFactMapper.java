package com.panjia.performance.mapper;

import com.panjia.performance.domain.PerformanceFact;
import com.panjia.performance.dto.PerformanceManageContractVO;
import com.panjia.performance.dto.PerformanceManageDTO;
import com.panjia.performance.dto.PerformanceManageEmployeeVO;
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
     * 业绩管理明细分页（以「签约人」为分页维度）。
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
     * 分页步骤：先 {@link #countManageEmployees} 数人，再 {@link #selectManagePageEmployees}
     * 取当前页员工的人维度聚合行（每人一行）；人下明细由 {@link #selectManageListByIds}
     * 在前端展开时按员工懒加载。仅查 ACTIVE 事实。
     *
     * @param period    归属期间（必填）
     * @param factType  事实口径（必填：PERF_REAL / PERF_EXPECT）
     * @param deptId    部门 ID（可选，含子部门，按员工归属部门过滤）
     * @param bizType   业务类型（可选）
     * @param settled   是否已结算（可选；null=全部，true=已结算，false=未结算）
     * @param keyword   关键字（可选：员工号/姓名/合同号/订单号/房源地址/角色/门店/店组 模糊匹配）
     * @param offset    偏移量（人数）
     * @param pageSize  每页人数
     * @return 当前页签约人聚合行（按姓名排序）
     */
    @Select("""
        <script>
        SELECT f.employee_id AS "employeeId",
               e.employee_code AS "employeeCode",
               e.employee_name AS "employeeName",
               MAX(
                   CASE
                       WHEN array_length(string_to_array(ed.ancestors, ','), 1) &gt;= 3 THEN
                           CONCAT_WS('-',
                               NULLIF(egp.dept_name, 'tenant_name'),
                               NULLIF(ep.dept_name, 'tenant_name'),
                               CASE WHEN ed.dept_name = ep.dept_name THEN NULL
                                    ELSE NULLIF(ed.dept_name, 'tenant_name') END)
                       ELSE
                           CONCAT_WS('-',
                               NULLIF(ep.dept_name, 'tenant_name'),
                               NULLIF(ed.dept_name, 'tenant_name'))
                   END
               ) AS "deptPath",
               COALESCE(SUM(f.origin_amount), 0) AS "amount",
               COUNT(DISTINCT rs.contract_no) AS "contractCount",
               COUNT(*) AS "detailCount",
               COUNT(*) FILTER (WHERE ci.id IS NULL) AS "unsettledCount"
        FROM pj_perf_fact f
        LEFT JOIN pj_people_employee e ON e.employee_id = f.employee_id
        LEFT JOIN sys_dept ed ON ed.dept_id = e.dept_id
        LEFT JOIN sys_dept ep ON ep.dept_id = ed.parent_id
        LEFT JOIN sys_dept egp ON egp.dept_id = ep.parent_id
        LEFT JOIN pj_normalized_record nr ON nr.id = f.normalized_record_id
        LEFT JOIN pj_import_raw_signed rs ON rs.id = nr.raw_data_id
        LEFT JOIN pj_commission_item ci ON ci.performance_fact_id = f.id
                                       AND ci.status &lt;&gt; 'REVERSED'
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
          <if test="keyword != null and keyword != ''">
            AND (
              e.employee_code ILIKE CONCAT('%', #{keyword}::text, '%')
              OR e.employee_name ILIKE CONCAT('%', #{keyword}::text, '%')
              OR rs.contract_no ILIKE CONCAT('%', #{keyword}::text, '%')
              OR rs.order_no ILIKE CONCAT('%', #{keyword}::text, '%')
              OR rs.raw_json -&gt;&gt; 'propertyAddress' ILIKE CONCAT('%', #{keyword}::text, '%')
              OR COALESCE(nr.role_type, f.role_type) ILIKE CONCAT('%', #{keyword}::text, '%')
              OR rs.raw_json -&gt;&gt; 'storeName' ILIKE CONCAT('%', #{keyword}::text, '%')
              OR rs.raw_json -&gt;&gt; 'deptName' ILIKE CONCAT('%', #{keyword}::text, '%')
            )
          </if>
          <if test="selfEmployeeId != null">
            AND f.employee_id = #{selfEmployeeId}
          </if>
        GROUP BY f.employee_id, e.employee_name, e.employee_code
        ORDER BY e.employee_name NULLS LAST, f.employee_id
        LIMIT #{pageSize} OFFSET #{offset}
        </script>
        """)
    List<PerformanceManageEmployeeVO> selectManagePageEmployees(@Param("period") String period,
                                           @Param("factType") String factType,
                                           @Param("deptId") Long deptId,
                                           @Param("bizType") String bizType,
                                           @Param("settled") Boolean settled,
                                           @Param("keyword") String keyword,
                                           @Param("selfEmployeeId") Long selfEmployeeId,
                                           @Param("offset") long offset,
                                           @Param("pageSize") int pageSize);

    /**
     * 统计符合条件的签约人数（分页 total）。参数语义同 {@link #selectManagePageEmployees}。
     */
    @Select("""
        <script>
        SELECT COUNT(DISTINCT f.employee_id)
        FROM pj_perf_fact f
        LEFT JOIN pj_people_employee e ON e.employee_id = f.employee_id
        LEFT JOIN pj_normalized_record nr ON nr.id = f.normalized_record_id
        LEFT JOIN pj_import_raw_signed rs ON rs.id = nr.raw_data_id
        LEFT JOIN pj_commission_item ci ON ci.performance_fact_id = f.id
                                       AND ci.status &lt;&gt; 'REVERSED'
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
          <if test="keyword != null and keyword != ''">
            AND (
              e.employee_code ILIKE CONCAT('%', #{keyword}::text, '%')
              OR e.employee_name ILIKE CONCAT('%', #{keyword}::text, '%')
              OR rs.contract_no ILIKE CONCAT('%', #{keyword}::text, '%')
              OR rs.order_no ILIKE CONCAT('%', #{keyword}::text, '%')
              OR rs.raw_json -&gt;&gt; 'propertyAddress' ILIKE CONCAT('%', #{keyword}::text, '%')
              OR COALESCE(nr.role_type, f.role_type) ILIKE CONCAT('%', #{keyword}::text, '%')
              OR rs.raw_json -&gt;&gt; 'storeName' ILIKE CONCAT('%', #{keyword}::text, '%')
              OR rs.raw_json -&gt;&gt; 'deptName' ILIKE CONCAT('%', #{keyword}::text, '%')
            )
          </if>
          <if test="selfEmployeeId != null">
            AND f.employee_id = #{selfEmployeeId}
          </if>
        </script>
        """)
    long countManageEmployees(@Param("period") String period,
                              @Param("factType") String factType,
                              @Param("deptId") Long deptId,
                              @Param("bizType") String bizType,
                              @Param("settled") Boolean settled,
                              @Param("keyword") String keyword,
                              @Param("selfEmployeeId") Long selfEmployeeId);

    /**
     * 查询指定员工集合的业绩明细（懒加载：展开人/全部展开时按员工 ID 查询），
     * 过滤条件与人维度分页查询保持一致，保证展开口径与人行合计吻合。
     */
    @Select("""
        <script>
        SELECT f.id,
               f.fact_type AS factType,
               f.period,
               COALESCE((rs.raw_json -&gt;&gt; 'signDate')::timestamp::date, f.business_date) AS businessDate,
               rs.order_no AS orderNo,
               rs.contract_no AS contractNo,
               f.biz_type AS bizType,
               rs.raw_json -&gt;&gt; 'propertyAddress' AS propertyAddress,
               f.employee_id AS employeeId,
               e.employee_name AS employeeName,
               e.employee_code AS employeeCode,
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
               END AS deptPath,
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
        LEFT JOIN sys_dept gp ON gp.dept_id = p.parent_id
        LEFT JOIN pj_normalized_record nr ON nr.id = f.normalized_record_id
        LEFT JOIN pj_import_raw_signed rs ON rs.id = nr.raw_data_id
        LEFT JOIN pj_commission_item ci ON ci.performance_fact_id = f.id
                                       AND ci.status &lt;&gt; 'REVERSED'
        LEFT JOIN pj_commission_application ca ON ca.id = ci.application_id
                                              AND ca.status IN ('APPROVED', 'LOCKED', 'CLOSED')
        WHERE f.fact_status = 'ACTIVE'
          AND f.period = #{period}
          AND f.fact_type = #{factType}
          AND f.employee_id IN
          <foreach collection="employeeIds" item="eid" open="(" separator="," close=")">#{eid}</foreach>
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
          <if test="keyword != null and keyword != ''">
            AND (
              e.employee_code ILIKE CONCAT('%', #{keyword}::text, '%')
              OR e.employee_name ILIKE CONCAT('%', #{keyword}::text, '%')
              OR rs.contract_no ILIKE CONCAT('%', #{keyword}::text, '%')
              OR rs.order_no ILIKE CONCAT('%', #{keyword}::text, '%')
              OR rs.raw_json -&gt;&gt; 'propertyAddress' ILIKE CONCAT('%', #{keyword}::text, '%')
              OR COALESCE(nr.role_type, f.role_type) ILIKE CONCAT('%', #{keyword}::text, '%')
              OR rs.raw_json -&gt;&gt; 'storeName' ILIKE CONCAT('%', #{keyword}::text, '%')
              OR rs.raw_json -&gt;&gt; 'deptName' ILIKE CONCAT('%', #{keyword}::text, '%')
            )
          </if>
          <if test="selfEmployeeId != null">
            AND f.employee_id = #{selfEmployeeId}
          </if>
        ORDER BY e.employee_name, rs.contract_no, businessDate, nr.role_type
        </script>
        """)
    List<PerformanceManageDTO> selectManageListByIds(@Param("period") String period,
                                                     @Param("factType") String factType,
                                                     @Param("deptId") Long deptId,
                                                     @Param("bizType") String bizType,
                                                     @Param("settled") Boolean settled,
                                                     @Param("keyword") String keyword,
                                                     @Param("selfEmployeeId") Long selfEmployeeId,
                                                     @Param("employeeIds") List<Long> employeeIds);

    /**
     * 全局汇总（与过滤条件一致，跨所有页）：明细数、合同数、金额合计、未结算条数。
     * 签约人数由 {@link #countManageEmployees} 给出。
     */
    @Select("""
        <script>
        SELECT COUNT(*) AS "detailCount",
               COUNT(DISTINCT rs.contract_no) AS "contractCount",
               COUNT(DISTINCT f.employee_id) AS "employeeCount",
               COALESCE(SUM(f.origin_amount), 0) AS "totalAmount",
               COUNT(*) FILTER (WHERE ci.id IS NULL) AS "unsettledCount"
        FROM pj_perf_fact f
        LEFT JOIN pj_people_employee e ON e.employee_id = f.employee_id
        LEFT JOIN pj_normalized_record nr ON nr.id = f.normalized_record_id
        LEFT JOIN pj_import_raw_signed rs ON rs.id = nr.raw_data_id
        LEFT JOIN pj_commission_item ci ON ci.performance_fact_id = f.id
                                       AND ci.status &lt;&gt; 'REVERSED'
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
          <if test="keyword != null and keyword != ''">
            AND (
              e.employee_code ILIKE CONCAT('%', #{keyword}::text, '%')
              OR e.employee_name ILIKE CONCAT('%', #{keyword}::text, '%')
              OR rs.contract_no ILIKE CONCAT('%', #{keyword}::text, '%')
              OR rs.order_no ILIKE CONCAT('%', #{keyword}::text, '%')
              OR rs.raw_json -&gt;&gt; 'propertyAddress' ILIKE CONCAT('%', #{keyword}::text, '%')
              OR COALESCE(nr.role_type, f.role_type) ILIKE CONCAT('%', #{keyword}::text, '%')
              OR rs.raw_json -&gt;&gt; 'storeName' ILIKE CONCAT('%', #{keyword}::text, '%')
              OR rs.raw_json -&gt;&gt; 'deptName' ILIKE CONCAT('%', #{keyword}::text, '%')
            )
          </if>
          <if test="selfEmployeeId != null">
            AND f.employee_id = #{selfEmployeeId}
          </if>
        </script>
        """)
    java.util.Map<String, Object> selectManageSummary(@Param("period") String period,
                                                      @Param("factType") String factType,
                                                      @Param("deptId") Long deptId,
                                                      @Param("bizType") String bizType,
                                                      @Param("settled") Boolean settled,
                                                      @Param("keyword") String keyword,
                                                      @Param("selfEmployeeId") Long selfEmployeeId);


    /**
     * 查询指定期间/口径下出现过的业务类型（去重排序），供筛选下拉使用。
     */
    @Select("""
        SELECT DISTINCT biz_type
        FROM pj_perf_fact
        WHERE fact_status = 'ACTIVE'
          AND period = #{period}
          AND fact_type = #{factType}
          AND biz_type IS NOT NULL
        ORDER BY 1
        """)
    List<String> selectManageBizTypes(@Param("period") String period,
                                      @Param("factType") String factType);

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

    // ==================== 合同维度 ====================

    /**
     * 业绩管理合同维度分页（以「合同号」为分页维度）。
     * <p>
     * 每合同一行：合同号/订单号/业务类型/房源地址/签约日期/合同金额合计/涉及人数/明细数/未结算数。
     * 合同下的签约人明细由 {@link #selectManageListByContractNos} 懒加载。仅查 ACTIVE 事实。
     *
     * @param period   归属期间（必填）
     * @param factType 事实口径（必填）
     * @param deptId   部门 ID（可选，含子部门）
     * @param bizType  业务类型（可选）
     * @param settled  是否已结算（可选）
     * @param keyword  关键字（可选：合同号/订单号/房源地址/员工号/姓名/角色/门店/店组）
     * @param offset   偏移量（合同数）
     * @param pageSize 每页合同数
     * @return 当前页合同聚合行（按签约日期倒序）
     */
    @Select("""
        <script>
        SELECT rs.contract_no AS "contractNo",
               MAX(rs.order_no) AS "orderNo",
               MAX(f.biz_type) AS "bizType",
               MAX(rs.raw_json -&gt;&gt; 'propertyAddress') AS "propertyAddress",
               MAX(COALESCE((rs.raw_json -&gt;&gt; 'signDate')::timestamp::date, f.business_date)) AS "businessDate",
               COALESCE(SUM(f.origin_amount), 0) AS "amount",
               COUNT(DISTINCT f.employee_id) AS "employeeCount",
               COUNT(*) AS "detailCount",
               COUNT(*) FILTER (WHERE ci.id IS NULL) AS "unsettledCount"
        FROM pj_perf_fact f
        LEFT JOIN pj_people_employee e ON e.employee_id = f.employee_id
        LEFT JOIN pj_normalized_record nr ON nr.id = f.normalized_record_id
        LEFT JOIN pj_import_raw_signed rs ON rs.id = nr.raw_data_id
        LEFT JOIN pj_commission_item ci ON ci.performance_fact_id = f.id
                                       AND ci.status &lt;&gt; 'REVERSED'
        WHERE f.fact_status = 'ACTIVE'
          AND f.period = #{period}
          AND f.fact_type = #{factType}
          AND rs.contract_no IS NOT NULL
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
          <if test="keyword != null and keyword != ''">
            AND (
              rs.contract_no ILIKE CONCAT('%', #{keyword}::text, '%')
              OR rs.order_no ILIKE CONCAT('%', #{keyword}::text, '%')
              OR rs.raw_json -&gt;&gt; 'propertyAddress' ILIKE CONCAT('%', #{keyword}::text, '%')
              OR e.employee_code ILIKE CONCAT('%', #{keyword}::text, '%')
              OR e.employee_name ILIKE CONCAT('%', #{keyword}::text, '%')
              OR COALESCE(nr.role_type, f.role_type) ILIKE CONCAT('%', #{keyword}::text, '%')
              OR rs.raw_json -&gt;&gt; 'storeName' ILIKE CONCAT('%', #{keyword}::text, '%')
              OR rs.raw_json -&gt;&gt; 'deptName' ILIKE CONCAT('%', #{keyword}::text, '%')
            )
          </if>
          <if test="selfEmployeeId != null">
            AND f.employee_id = #{selfEmployeeId}
          </if>
        GROUP BY rs.contract_no
        ORDER BY "businessDate" DESC, rs.contract_no
        LIMIT #{pageSize} OFFSET #{offset}
        </script>
        """)
    List<PerformanceManageContractVO> selectManagePageContracts(@Param("period") String period,
                                            @Param("factType") String factType,
                                            @Param("deptId") Long deptId,
                                            @Param("bizType") String bizType,
                                            @Param("settled") Boolean settled,
                                            @Param("keyword") String keyword,
                                            @Param("selfEmployeeId") Long selfEmployeeId,
                                            @Param("offset") long offset,
                                            @Param("pageSize") int pageSize);

    /**
     * 统计符合条件的合同数（分页 total）。参数语义同 {@link #selectManagePageContracts}。
     */
    @Select("""
        <script>
        SELECT COUNT(DISTINCT rs.contract_no)
        FROM pj_perf_fact f
        LEFT JOIN pj_people_employee e ON e.employee_id = f.employee_id
        LEFT JOIN pj_normalized_record nr ON nr.id = f.normalized_record_id
        LEFT JOIN pj_import_raw_signed rs ON rs.id = nr.raw_data_id
        LEFT JOIN pj_commission_item ci ON ci.performance_fact_id = f.id
                                       AND ci.status &lt;&gt; 'REVERSED'
        WHERE f.fact_status = 'ACTIVE'
          AND f.period = #{period}
          AND f.fact_type = #{factType}
          AND rs.contract_no IS NOT NULL
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
          <if test="keyword != null and keyword != ''">
            AND (
              rs.contract_no ILIKE CONCAT('%', #{keyword}::text, '%')
              OR rs.order_no ILIKE CONCAT('%', #{keyword}::text, '%')
              OR rs.raw_json -&gt;&gt; 'propertyAddress' ILIKE CONCAT('%', #{keyword}::text, '%')
              OR e.employee_code ILIKE CONCAT('%', #{keyword}::text, '%')
              OR e.employee_name ILIKE CONCAT('%', #{keyword}::text, '%')
              OR COALESCE(nr.role_type, f.role_type) ILIKE CONCAT('%', #{keyword}::text, '%')
              OR rs.raw_json -&gt;&gt; 'storeName' ILIKE CONCAT('%', #{keyword}::text, '%')
              OR rs.raw_json -&gt;&gt; 'deptName' ILIKE CONCAT('%', #{keyword}::text, '%')
            )
          </if>
          <if test="selfEmployeeId != null">
            AND f.employee_id = #{selfEmployeeId}
          </if>
        </script>
        """)
    long countManageContracts(@Param("period") String period,
                              @Param("factType") String factType,
                              @Param("deptId") Long deptId,
                              @Param("bizType") String bizType,
                              @Param("settled") Boolean settled,
                              @Param("keyword") String keyword,
                              @Param("selfEmployeeId") Long selfEmployeeId);

    /**
     * 按合同号集合查询业绩明细（合同维度树表懒加载数据源）。
     * <p>
     * 过滤条件与 {@link #selectManagePageContracts} 一致，返回该合同下所有签约人的明细行，
     * 前端按「合同 → 人 → 明细」组装树。
     *
     * @param contractNos 合同号集合（不能为空）
     */
    @Select("""
        <script>
        SELECT f.id,
               f.fact_type AS factType,
               f.period,
               COALESCE((rs.raw_json -&gt;&gt; 'signDate')::timestamp::date, f.business_date) AS businessDate,
               rs.order_no AS orderNo,
               rs.contract_no AS contractNo,
               f.biz_type AS bizType,
               rs.raw_json -&gt;&gt; 'propertyAddress' AS propertyAddress,
               f.employee_id AS employeeId,
               e.employee_name AS employeeName,
               e.employee_code AS employeeCode,
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
               END AS deptPath,
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
        LEFT JOIN sys_dept gp ON gp.dept_id = p.parent_id
        LEFT JOIN pj_normalized_record nr ON nr.id = f.normalized_record_id
        LEFT JOIN pj_import_raw_signed rs ON rs.id = nr.raw_data_id
        LEFT JOIN pj_commission_item ci ON ci.performance_fact_id = f.id
                                       AND ci.status &lt;&gt; 'REVERSED'
        LEFT JOIN pj_commission_application ca ON ca.id = ci.application_id
                                              AND ca.status IN ('APPROVED', 'LOCKED', 'CLOSED')
        WHERE f.fact_status = 'ACTIVE'
          AND f.period = #{period}
          AND f.fact_type = #{factType}
          AND rs.contract_no IN
          <foreach collection="contractNos" item="cn" open="(" separator="," close=")">#{cn}</foreach>
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
          <if test="keyword != null and keyword != ''">
            AND (
              rs.contract_no ILIKE CONCAT('%', #{keyword}::text, '%')
              OR rs.order_no ILIKE CONCAT('%', #{keyword}::text, '%')
              OR rs.raw_json -&gt;&gt; 'propertyAddress' ILIKE CONCAT('%', #{keyword}::text, '%')
              OR e.employee_code ILIKE CONCAT('%', #{keyword}::text, '%')
              OR e.employee_name ILIKE CONCAT('%', #{keyword}::text, '%')
              OR COALESCE(nr.role_type, f.role_type) ILIKE CONCAT('%', #{keyword}::text, '%')
              OR rs.raw_json -&gt;&gt; 'storeName' ILIKE CONCAT('%', #{keyword}::text, '%')
              OR rs.raw_json -&gt;&gt; 'deptName' ILIKE CONCAT('%', #{keyword}::text, '%')
            )
          </if>
        ORDER BY rs.contract_no, e.employee_name, businessDate, nr.role_type
        </script>
        """)
    List<PerformanceManageDTO> selectManageListByContractNos(@Param("period") String period,
                                                             @Param("factType") String factType,
                                                             @Param("deptId") Long deptId,
                                                             @Param("bizType") String bizType,
                                                             @Param("settled") Boolean settled,
                                                             @Param("keyword") String keyword,
                                                             @Param("contractNos") List<String> contractNos);
}
