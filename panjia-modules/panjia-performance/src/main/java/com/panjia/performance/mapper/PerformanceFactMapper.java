package com.panjia.performance.mapper;

import com.panjia.contracts.dto.PerformanceContractSummaryDTO;
import com.panjia.contracts.dto.PerformanceFactSummaryDTO;
import com.panjia.performance.domain.PerformanceFact;
import com.panjia.performance.dto.AdjustFactDetailDTO;
import com.panjia.performance.dto.PerformanceFactSearchDTO;
import com.panjia.performance.dto.PerformanceManageContractVO;
import com.panjia.performance.dto.PerformanceManageDTO;
import com.panjia.performance.dto.PerformanceManageEmployeeVO;
import com.panjia.performance.dto.PerformanceSearchDetailDTO;
import com.panjia.performance.dto.ReceivedContractMetricsDTO;
import com.panjia.performance.dto.ReceivedFactDetailDTO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;

import java.util.Collection;
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
               COALESCE(SUM(f.performance_amount), 0) AS "amount",
               COALESCE(SUM(
                 COALESCE(
                   (SELECT pf.performance_amount FROM pj_perf_fact pf
                    WHERE pf.source_key = f.source_key
                      AND pf.fact_type = f.fact_type
                      AND pf.fact_status = 'REVERSED'
                    ORDER BY pf.id ASC LIMIT 1),
                   f.performance_amount
                 )
               ), 0) AS "originalAmount",
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
               COALESCE((rs.raw_json -&gt;&gt; 'signDate')::timestamp, f.business_date::timestamp) AS businessDate,
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
               f.performance_amount AS amount,
               COALESCE(
                 (SELECT pf.performance_amount FROM pj_perf_fact pf
                  WHERE pf.source_key = f.source_key
                    AND pf.fact_type = f.fact_type
                    AND pf.fact_status = 'REVERSED'
                  ORDER BY pf.id ASC LIMIT 1),
                 f.performance_amount
               ) AS originalAmount,
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
               COUNT(DISTINCT CASE WHEN f.biz_type IN ('一手房','房产金融','家装荐客')
                                   THEN COALESCE(rs.order_no, rs.contract_no)
                                   ELSE COALESCE(rs.contract_no, rs.order_no) END) AS "contractCount",
               COUNT(DISTINCT f.employee_id) AS "employeeCount",
               COALESCE(SUM(f.performance_amount), 0) AS "totalAmount",
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

    /*
     * 业务键（合同号/订单号）统一口径——凡按「合同」聚合/匹配的查询均使用该规则
     * （前端 src/utils/panjiaBiz.ts 的 resolveBizNo 与之同步，改动须两侧一致）：
     * - 一手房、房产金融、家装荐客：以订单号为准（订单号为空回退合同号）；
     * - 其它类型：以合同号为准（合同号为空回退订单号）。
     * 键表达式（f/rs 为本 Mapper 查询的固定别名）：
     *   CASE WHEN f.biz_type IN ('一手房','房产金融','家装荐客')
     *        THEN COALESCE(rs.order_no, rs.contract_no)
     *        ELSE COALESCE(rs.contract_no, rs.order_no) END
     * 单据（调整/实收/结佣）按单据上存的合同号匹配时用「或」形式（兼容合同号/订单号两种落库键）。
     */

    /**
     * 业绩管理合同维度分页（以「业务键」为分页维度）。
     * <p>
     * 每业务键一行：合同号/订单号/业务类型/房源地址/签约日期/合同金额合计/涉及人数/明细数/未结算数。
     * 一手房、房产金融、家装荐客按订单号聚合；其它按合同号聚合（合同号为空回退订单号）。
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
        SELECT MAX(rs.contract_no) AS "contractNo",
               MAX(rs.order_no) AS "orderNo",
               MAX(f.biz_type) AS "bizType",
               MAX(rs.raw_json -&gt;&gt; 'propertyAddress') AS "propertyAddress",
               MAX(COALESCE((rs.raw_json -&gt;&gt; 'signDate')::timestamp, f.business_date::timestamp)) AS "businessDate",
               COALESCE(SUM(f.performance_amount), 0) AS "amount",
               COALESCE(SUM(
                 COALESCE(
                   (SELECT pf.performance_amount FROM pj_perf_fact pf
                    WHERE pf.source_key = f.source_key
                      AND pf.fact_type = f.fact_type
                      AND pf.fact_status = 'REVERSED'
                    ORDER BY pf.id ASC LIMIT 1),
                   f.performance_amount
                 )
               ), 0) AS "originalAmount",
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
          AND CASE WHEN f.biz_type IN ('一手房','房产金融','家装荐客')
                   THEN COALESCE(rs.order_no, rs.contract_no)
                   ELSE COALESCE(rs.contract_no, rs.order_no) END IS NOT NULL
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
        GROUP BY CASE WHEN f.biz_type IN ('一手房','房产金融','家装荐客')
                     THEN COALESCE(rs.order_no, rs.contract_no)
                     ELSE COALESCE(rs.contract_no, rs.order_no) END
        ORDER BY "businessDate" DESC, "contractNo"
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
     * 统计符合条件的合同数（分页 total，按业务键去重：订单键类型按订单号，其余按合同号）。
     * 参数语义同 {@link #selectManagePageContracts}。
     */
    @Select("""
        <script>
        SELECT COUNT(DISTINCT CASE WHEN f.biz_type IN ('一手房','房产金融','家装荐客')
                                    THEN COALESCE(rs.order_no, rs.contract_no)
                                    ELSE COALESCE(rs.contract_no, rs.order_no) END)
        FROM pj_perf_fact f
        LEFT JOIN pj_people_employee e ON e.employee_id = f.employee_id
        LEFT JOIN pj_normalized_record nr ON nr.id = f.normalized_record_id
        LEFT JOIN pj_import_raw_signed rs ON rs.id = nr.raw_data_id
        LEFT JOIN pj_commission_item ci ON ci.performance_fact_id = f.id
                                       AND ci.status &lt;&gt; 'REVERSED'
        WHERE f.fact_status = 'ACTIVE'
          AND f.period = #{period}
          AND f.fact_type = #{factType}
          AND CASE WHEN f.biz_type IN ('一手房','房产金融','家装荐客')
                   THEN COALESCE(rs.order_no, rs.contract_no)
                   ELSE COALESCE(rs.contract_no, rs.order_no) END IS NOT NULL
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
     * 按业务键集合查询业绩明细（合同维度树表懒加载数据源）。
     * <p>
     * 键口径与 {@link #selectManagePageContracts} 一致：一手房、房产金融、家装荐客传订单号，
     * 其余传合同号（合同号为空回退订单号，即列表行展示的键）。
     * 过滤条件与 {@link #selectManagePageContracts} 一致，返回该业务键下所有签约人的明细行，
     * 前端按「合同 → 人 → 明细」组装树。
     *
     * @param contractNos 业务键集合（不能为空；列表行展示的合同号/订单号）
     */
    @Select("""
        <script>
        SELECT f.id,
               f.fact_type AS factType,
               f.period,
               COALESCE((rs.raw_json -&gt;&gt; 'signDate')::timestamp, f.business_date::timestamp) AS businessDate,
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
               f.performance_amount AS amount,
               COALESCE(
                 (SELECT pf.performance_amount FROM pj_perf_fact pf
                  WHERE pf.source_key = f.source_key
                    AND pf.fact_type = f.fact_type
                    AND pf.fact_status = 'REVERSED'
                  ORDER BY pf.id ASC LIMIT 1),
                 f.performance_amount
               ) AS originalAmount,
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
          AND CASE WHEN f.biz_type IN ('一手房','房产金融','家装荐客')
                   THEN COALESCE(rs.order_no, rs.contract_no)
                   ELSE COALESCE(rs.contract_no, rs.order_no) END IN
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

    /**
     * 查询指定合同号下全部 ACTIVE 业绩事实（合同级调整时按比例分摊用）。
     * <p>
     * 通过 normalized_record → raw_signed 关联 contract_no 定位同合同的所有明细事实。
     *
     * @param period     归属期间
     * @param factType   事实口径
     * @param contractNo 合同号
     * @return 该合同下全部 ACTIVE 事实列表
     */
    @Select("""
        SELECT f.*
        FROM pj_perf_fact f
        JOIN pj_normalized_record nr ON nr.id = f.normalized_record_id
        JOIN pj_import_raw_signed rs ON rs.id = nr.raw_data_id
        WHERE f.fact_status = 'ACTIVE'
          AND f.period = #{period}
          AND f.fact_type = #{factType}
          AND (rs.contract_no = #{contractNo}
               OR (CASE WHEN f.biz_type IN ('一手房','房产金融','家装荐客')
                        THEN COALESCE(rs.order_no, rs.contract_no)
                        ELSE COALESCE(rs.contract_no, rs.order_no) END) = #{contractNo})
        ORDER BY f.id
        """)
    List<PerformanceFact> selectActiveFactsByContractNo(@Param("period") String period,
                                                         @Param("factType") String factType,
                                                         @Param("contractNo") String contractNo);

    /**
     * 按业务键前缀定位退单红冲对应的原正数 ACTIVE 事实（成交月原事实）。
     * <p>
     * 事实 source_key = {@code sourceType-recordSourceKey-period}，同一笔业务
     * （订单|合同|角色人|费项|角色类型）在成交月与退单月仅末尾 period 不同，
     * 故用 POSITION 做严格前缀匹配（避免 LIKE 下业务键含 _ / % 的歧义），
     * 取最早期间的一条正数事实作为红冲镜像源。
     *
     * @param sourceKeyPrefix 业务键前缀（sourceType + "-" + recordSourceKey + "-"，不含 period）
     * @param factType        事实口径
     * @return 最早的正数 ACTIVE 原事实；无则返回 null
     */
    @Select("""
        SELECT f.*
        FROM pj_perf_fact f
        WHERE f.fact_status = 'ACTIVE'
          AND f.fact_type = #{factType}
          AND f.performance_amount > 0
          AND POSITION(#{sourceKeyPrefix} IN f.source_key) = 1
          AND LENGTH(f.source_key) = LENGTH(#{sourceKeyPrefix}) + 7
        ORDER BY f.period ASC, f.id ASC
        LIMIT 1
        """)
    PerformanceFact selectOriginalPositiveFact(@Param("sourceKeyPrefix") String sourceKeyPrefix,
                                               @Param("factType") String factType);

    /**
     * 按业务键前缀汇总更早期间已 ACTIVE 认列事实的「贝壳当前金额」合计，
     * 用于跨月重复导入时应收「只认一次」的增量认定（§双口径契约）。
     * <p>
     * 当前金额 = performance_amount（即导入的折后金额）；
     * 仅统计 ACTIVE 事实，被 supersede/冲销的历史不认列不参与。
     *
     * @param sourceKeyPrefix 业务键前缀（订单|合同|角色人|费项|角色类型）
     * @param factType        事实口径（PERF_EXPECT）
     * @param period          当前导入期间（仅统计更早期间）
     * @return 已认列当前金额合计（无历史返回 0）
     */
    @Select("""
        <script>
        SELECT COALESCE(SUM(f.performance_amount), 0)
        FROM pj_perf_fact f
        WHERE f.fact_status = 'ACTIVE'
          AND f.fact_type = #{factType}
          AND f.period &lt; #{period}
          AND POSITION(#{sourceKeyPrefix} IN f.source_key) = 1
        </script>
        """)
    java.math.BigDecimal sumRecognizedCurrentByPrefix(@Param("sourceKeyPrefix") String sourceKeyPrefix,
                                                      @Param("factType") String factType,
                                                      @Param("period") String period);

    /**
     * 查询某合同在更早期间已认列事实所携带的最大「合同累计应收（总应收业绩）」。
     * <p>
     * 该列在合同每个角色行重复出现，取 MAX 即合同口径历史累计值；
     * 仅取 ACTIVE 应收事实关联的归一化行，天然排除已 supersede 批次。
     *
     * @param contractNo 合同号
     * @param period     当前导入期间
     * @return 历史最大合同累计应收（无历史返回 0）
     */
    @Select("""
        <script>
        SELECT COALESCE(MAX(n.total_receivable_amount), 0)
        FROM pj_perf_fact f
        JOIN pj_normalized_record n ON n.id = f.normalized_record_id
        JOIN pj_import_raw_signed rs ON rs.id = n.raw_data_id
        WHERE f.fact_type = 'PERF_EXPECT'
          AND f.fact_status = 'ACTIVE'
          AND f.period &lt; #{period}
          AND rs.contract_no = #{contractNo}
        </script>
        """)
    java.math.BigDecimal selectMaxPriorContractTotalReceivable(@Param("contractNo") String contractNo,
                                                               @Param("period") String period);

    /**
     * 按事实 ID 集合查询事实摘要（含合同号/订单号/房源地址）。
     * <p>
     * 供结佣域按合同维度展示申请单列表使用，通过 normalized_record → raw_signed
     * 关联取出合同维度字段。不限状态（溯源需能看到已冲销事实）。
     *
     * @param factIds 事实 ID 集合
     * @return 事实摘要列表（仅存在的 ID）
     */
    @Select("""
        <script>
        SELECT f.id AS "factId",
               f.fact_type AS "factType",
               f.fact_status AS "factStatus",
               f.period AS "period",
               f.business_date AS "businessDate",
               f.employee_id AS "employeeId",
               f.employee_external_code AS "employeeCode",
               f.dept_id AS "deptId",
               f.biz_type AS "bizType",
               f.role_type AS "roleType",
               f.performance_amount AS "amount",
               f.batch_id AS "batchId",
               f.normalized_record_id AS "normalizedRecordId",
               f.source_key AS "sourceKey",
               f.received_apply_id AS "receivedApplyId",
               ra.status AS "receivedStatus",
               rs.contract_no AS "contractNo",
               rs.order_no AS "orderNo",
               rs.raw_json ->> 'propertyAddress' AS "propertyAddress"
        FROM pj_perf_fact f
        LEFT JOIN pj_normalized_record nr ON nr.id = f.normalized_record_id
        LEFT JOIN pj_import_raw_signed rs ON rs.id = nr.raw_data_id
        LEFT JOIN pj_perf_received_apply ra ON ra.id = f.received_apply_id
        WHERE f.id IN
        <foreach collection="factIds" item="id" open="(" separator="," close=")">#{id}</foreach>
        ORDER BY f.id
        </script>
        """)
    List<PerformanceFactSummaryDTO> selectFactSummariesByIds(@Param("factIds") Collection<Long> factIds);

    /**
     * 按期间 + 合同号查询 ACTIVE 事实摘要（含合同号/订单号/房源地址，结佣按合同发起用）。
     *
     * @param period     归属期间
     * @param factType   事实口径
     * @param contractNo 合同号
     * @return 事实摘要列表（含 amount = 0 的行，过滤由结佣域处理）
     */
    @Select("""
        SELECT f.id AS "factId",
               f.fact_type AS "factType",
               f.fact_status AS "factStatus",
               f.period AS "period",
               f.business_date AS "businessDate",
               f.employee_id AS "employeeId",
               f.employee_external_code AS "employeeCode",
               f.dept_id AS "deptId",
               f.biz_type AS "bizType",
               f.role_type AS "roleType",
               f.performance_amount AS "amount",
               f.batch_id AS "batchId",
               f.normalized_record_id AS "normalizedRecordId",
               f.source_key AS "sourceKey",
               f.received_apply_id AS "receivedApplyId",
               ra.status AS "receivedStatus",
               rs.contract_no AS "contractNo",
               rs.order_no AS "orderNo",
               rs.raw_json ->> 'propertyAddress' AS "propertyAddress"
        FROM pj_perf_fact f
        JOIN pj_normalized_record nr ON nr.id = f.normalized_record_id
        JOIN pj_import_raw_signed rs ON rs.id = nr.raw_data_id
        LEFT JOIN pj_perf_received_apply ra ON ra.id = f.received_apply_id
        WHERE f.fact_status = 'ACTIVE'
          AND f.period = #{period}
          AND f.fact_type = #{factType}
          AND (rs.contract_no = #{contractNo}
               OR (CASE WHEN f.biz_type IN ('一手房','房产金融','家装荐客')
                        THEN COALESCE(rs.order_no, rs.contract_no)
                        ELSE COALESCE(rs.contract_no, rs.order_no) END) = #{contractNo})
        ORDER BY f.id
        """)
    List<PerformanceFactSummaryDTO> selectActiveFactSummariesByContractNo(@Param("period") String period,
                                                                          @Param("factType") String factType,
                                                                          @Param("contractNo") String contractNo);

    /**
     * 按期间 + 合同号查询实收审批单详情明细（每人一行，含应收/实收双口径）。
     * <p>
     * 列口径对齐「合同业绩明细」页（selectManageListByContractNos）：
     * deptPath / 工号 / 姓名 / 角色 / 角色占比；应收金额按同 sourceKey 的
     * PERF_EXPECT 事实配对（导入引擎一行双发，与 ReceivedAlignmentService 口径一致）。
     *
     * @param period     归属期间
     * @param contractNo 合同号
     * @return 每人实收明细行
     */
    @Select("""
        <script>
        SELECT f.id AS "factId",
               f.employee_id AS "employeeId",
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
               COALESCE(nr.role_type, f.role_type) AS "roleType",
               rs.role_name AS "roleName",
               f.share_ratio AS "shareRatio",
               (SELECT pe.performance_amount
                  FROM pj_perf_fact pe
                 WHERE pe.fact_status = 'ACTIVE'
                   AND pe.fact_type = 'PERF_EXPECT'
                   AND pe.source_key = f.source_key
                 ORDER BY pe.id
                 LIMIT 1) AS "expectedAmount",
               EXISTS(SELECT 1 FROM pj_perf_fact pe2
                 WHERE pe2.fact_status = 'REVERSED'
                   AND pe2.fact_type = 'PERF_EXPECT'
                   AND pe2.source_key = f.source_key) AS "expectedAdjusted",
               f.performance_amount AS "amount"
        FROM pj_perf_fact f
        LEFT JOIN pj_people_employee e ON e.employee_id = f.employee_id
        LEFT JOIN sys_dept d ON d.dept_id = f.dept_id
        LEFT JOIN sys_dept p ON p.dept_id = d.parent_id
        LEFT JOIN sys_dept gp ON gp.dept_id = p.parent_id
        LEFT JOIN pj_normalized_record nr ON nr.id = f.normalized_record_id
        LEFT JOIN pj_import_raw_signed rs ON rs.id = nr.raw_data_id
        WHERE f.fact_status = 'ACTIVE'
          AND f.period = #{period}
          AND f.fact_type = 'PERF_REAL'
          AND (rs.contract_no = #{contractNo}
               OR (CASE WHEN f.biz_type IN ('一手房','房产金融','家装荐客')
                        THEN COALESCE(rs.order_no, rs.contract_no)
                        ELSE COALESCE(rs.contract_no, rs.order_no) END) = #{contractNo})
        ORDER BY e.employee_name, d.dept_id, nr.role_type, f.id
        </script>
        """)
    List<ReceivedFactDetailDTO> selectReceivedFactDetails(@Param("period") String period,
                                                           @Param("contractNo") String contractNo);

    /**
     * 按期间 + 合同号集合查询实收明细列表的补充字段（业务类型、涉及人数、应收合计），每传入键一行。
     * <p>
     * 实收审批单表不存这几个字段，列表页按 (period, contractNo) 从 ACTIVE 事实聚合回填，
     * 口径与详情弹窗的「每人实收明细」一致（同期间同口径）。
     * 应收合计取 ACTIVE PERF_EXPECT（含已生效调整），使列表「新签业绩」显示调整后金额。
     * 匹配口径：传入键既可命中按合同号落库的事实，也可命中按订单号聚合的事实
     * （一手房、房产金融、家装荐客以订单号为准）。
     *
     * @param period      归属期间
     * @param contractNos 单据上的合同号/业务键集合（不可为空，调用方需先过滤）
     * @return 每键一行的业务类型、涉及人数与应收合计
     */
    @Select("""
        <script>
        SELECT k.key AS "contractNo",
               MAX(f.biz_type) AS "bizType",
               COUNT(DISTINCT f.employee_id) AS "employeeCount",
               COALESCE((
                   SELECT SUM(e.performance_amount)
                   FROM pj_perf_fact e
                   JOIN pj_normalized_record enr ON enr.id = e.normalized_record_id
                   JOIN pj_import_raw_signed ers ON ers.id = enr.raw_data_id
                   WHERE e.fact_status = 'ACTIVE' AND e.fact_type = 'PERF_EXPECT'
                     AND e.period = #{period}
                     AND (ers.contract_no = k.key
                          OR (CASE WHEN e.biz_type IN ('一手房','房产金融','家装荐客')
                                   THEN COALESCE(ers.order_no, ers.contract_no)
                                   ELSE COALESCE(ers.contract_no, ers.order_no) END) = k.key)
               ), 0) AS "expectedAmount"
        FROM (VALUES
          <foreach collection="contractNos" item="cn" separator=",">(#{cn})</foreach>
        ) AS k(key)
        JOIN pj_perf_fact f ON f.fact_status = 'ACTIVE'
                           AND f.fact_type = 'PERF_REAL'
                           AND f.period = #{period}
        JOIN pj_normalized_record nr ON nr.id = f.normalized_record_id
        JOIN pj_import_raw_signed rs ON rs.id = nr.raw_data_id
        WHERE (rs.contract_no = k.key
               OR (CASE WHEN f.biz_type IN ('一手房','房产金融','家装荐客')
                        THEN COALESCE(rs.order_no, rs.contract_no)
                        ELSE COALESCE(rs.contract_no, rs.order_no) END) = k.key)
        GROUP BY k.key
        </script>
        """)
    List<ReceivedContractMetricsDTO> selectReceivedContractMetrics(
        @Param("period") String period,
        @Param("contractNos") Collection<String> contractNos);

    /**
     * 按期间查询「合同」维度业绩汇总（结佣申请列表合并展示用）。
     * <p>
     * 按业务键聚合：一手房/房产金融/家装荐客以订单号为准（空回退合同号），
     * 其余以合同号为准（空回退订单号）；返回的 contractNo 即业务键，
     * 结佣申请单的存储键与幂等匹配（findActiveApplication / uk_capp_period_contract）均以此为准。
     * deptId 非空时含下级部门（与业绩明细页口径一致）。
     *
     * @param period   归属期间
     * @param factType 事实口径
     * @param deptId   门店 ID（可空）
     * @return 合同维度摘要列表（contractNo = 业务键）
     */
    @Select("""
        <script>
        SELECT s.biz_key AS "contractNo",
               MAX(s.order_no) AS "orderNo",
               MAX(s.biz_type) AS "bizType",
               MAX(s.property_address) AS "propertyAddress",
               MAX(s.business_date) AS "businessDate",
               COALESCE(SUM(s.amount), 0) AS "amount",
               COALESCE((
                   SELECT SUM(e.performance_amount)
                   FROM pj_perf_fact e
                   JOIN pj_normalized_record enr ON enr.id = e.normalized_record_id
                   JOIN pj_import_raw_signed ers ON ers.id = enr.raw_data_id
                   WHERE e.fact_status = 'ACTIVE' AND e.fact_type = 'PERF_EXPECT'
                     AND e.period = #{period}
                     AND (CASE WHEN e.biz_type IN ('一手房','房产金融','家装荐客')
                               THEN COALESCE(ers.order_no, ers.contract_no)
                               ELSE COALESCE(ers.contract_no, ers.order_no) END) = s.biz_key
               ), 0) AS "expectedAmount",
               CASE
                   WHEN bool_or(s.ra_status = 'SUBMITTED') THEN 'SUBMITTED'
                   WHEN bool_or(s.ra_status = 'DRAFT') THEN 'DRAFT'
                   WHEN COUNT(*) = COUNT(s.ra_id) FILTER (WHERE s.ra_status = 'APPROVED') THEN 'APPROVED'
                   ELSE NULL
               END AS "receivedStatus",
               COUNT(DISTINCT s.employee_id) AS "employeeCount",
               COUNT(*) AS "detailCount"
        FROM (
            SELECT CASE WHEN f.biz_type IN ('一手房','房产金融','家装荐客')
                        THEN COALESCE(rs.order_no, rs.contract_no)
                        ELSE COALESCE(rs.contract_no, rs.order_no) END AS biz_key,
                   rs.order_no,
                   f.biz_type,
                   rs.raw_json ->> 'propertyAddress' AS property_address,
                   COALESCE((rs.raw_json ->> 'signDate')::timestamp, f.business_date::timestamp) AS business_date,
                   f.performance_amount AS amount,
                   ra.status AS ra_status,
                   ra.id AS ra_id,
                   f.employee_id
            FROM pj_perf_fact f
            JOIN pj_normalized_record nr ON nr.id = f.normalized_record_id
            JOIN pj_import_raw_signed rs ON rs.id = nr.raw_data_id
            LEFT JOIN pj_perf_received_apply ra ON ra.id = f.received_apply_id
            WHERE f.fact_status = 'ACTIVE'
              AND f.period = #{period}
              AND f.fact_type = #{factType}
              AND CASE WHEN f.biz_type IN ('一手房','房产金融','家装荐客')
                       THEN COALESCE(rs.order_no, rs.contract_no)
                       ELSE COALESCE(rs.contract_no, rs.order_no) END IS NOT NULL
            <if test="deptId != null">
              AND (f.dept_id = #{deptId}
                   OR EXISTS (SELECT 1 FROM sys_dept sd WHERE sd.dept_id = f.dept_id
                              AND sd.ancestors LIKE CONCAT('%', #{deptId}, '%')))
            </if>
        ) s
        GROUP BY s.biz_key
        ORDER BY MAX(s.business_date) DESC, s.biz_key
        </script>
        """)
    List<PerformanceContractSummaryDTO> selectContractSummaries(@Param("period") String period,
                                                                 @Param("factType") String factType,
                                                                 @Param("deptId") Long deptId);

    /**
     * 按导入批次聚合「实收业绩合同组」（实收审批单自动建单用，§2.1）。
     * <p>
     * 仅取本批新建、ACTIVE、尚未挂实收审批单（received_apply_id IS NULL）的 PERF_REAL 事实，
     * 按业务键聚合（一手房/房产金融/家装荐客取订单号，其余取合同号、空回退订单号）：
     * 实收合计、同键应收合计（PERF_EXPECT）、快照字段、明细条数。
     * 输出的 contractNo 即业务键，实收审批单的存储键 / 批量审批匹配均以此为准。
     *
     * @param batchId 导入批次 ID
     * @param period  归属期间
     * @return 合同聚合组列表（contractNo = 业务键）
     */
    @Select("""
        SELECT s.biz_key AS "contractNo",
               MAX(s.order_no) AS "orderNo",
               MAX(s.property_address) AS "propertyAddress",
               MAX(s.business_date) AS "businessDate",
               COALESCE(SUM(s.amount), 0) AS "receivedAmount",
               COALESCE((
                   SELECT SUM(e.performance_amount)
                   FROM pj_perf_fact e
                   JOIN pj_normalized_record enr ON enr.id = e.normalized_record_id
                   JOIN pj_import_raw_signed ers ON ers.id = enr.raw_data_id
                   WHERE e.fact_status = 'ACTIVE' AND e.fact_type = 'PERF_EXPECT'
                     AND e.period = #{period}
                     AND (CASE WHEN e.biz_type IN ('一手房','房产金融','家装荐客')
                               THEN COALESCE(ers.order_no, ers.contract_no)
                               ELSE COALESCE(ers.contract_no, ers.order_no) END) = s.biz_key
               ), 0) AS "expectedAmount",
               COUNT(*) AS "itemCount"
        FROM (
            SELECT CASE WHEN f.biz_type IN ('一手房','房产金融','家装荐客')
                        THEN COALESCE(rs.order_no, rs.contract_no)
                        ELSE COALESCE(rs.contract_no, rs.order_no) END AS biz_key,
                   rs.order_no,
                   rs.raw_json ->> 'propertyAddress' AS property_address,
                   COALESCE((rs.raw_json ->> 'signDate')::timestamp, f.business_date::timestamp) AS business_date,
                   f.performance_amount AS amount
            FROM pj_perf_fact f
            JOIN pj_normalized_record nr ON nr.id = f.normalized_record_id
            JOIN pj_import_raw_signed rs ON rs.id = nr.raw_data_id
            WHERE f.fact_status = 'ACTIVE'
              AND f.fact_type = 'PERF_REAL'
              AND f.batch_id = #{batchId}
              AND f.period = #{period}
              AND f.received_apply_id IS NULL
              AND CASE WHEN f.biz_type IN ('一手房','房产金融','家装荐客')
                       THEN COALESCE(rs.order_no, rs.contract_no)
                       ELSE COALESCE(rs.contract_no, rs.order_no) END IS NOT NULL
        ) s
        GROUP BY s.biz_key
        HAVING COALESCE(SUM(s.amount), 0) <> 0
        ORDER BY s.biz_key
        """)
    List<com.panjia.performance.dto.ReceivedContractGroupDTO> selectBatchReceivedContractGroups(
        @Param("batchId") Long batchId, @Param("period") String period);

    /**
     * 按事实 ID 查询所属合同的基本信息（调整单详情展示用）。
     *
     * @param factId 事实 ID
     * @return 合同摘要；查不到返回 null
     */
    @Select("""
        SELECT rs.contract_no AS "contractNo",
               MAX(rs.order_no) AS "orderNo",
               MAX(f.biz_type) AS "bizType",
               MAX(rs.raw_json ->> 'propertyAddress') AS "propertyAddress",
               MAX(COALESCE((rs.raw_json ->> 'signDate')::timestamp, f.business_date::timestamp)) AS "businessDate"
        FROM pj_perf_fact f
        JOIN pj_normalized_record nr ON nr.id = f.normalized_record_id
        JOIN pj_import_raw_signed rs ON rs.id = nr.raw_data_id
        WHERE f.id = #{factId}
          AND rs.contract_no IS NOT NULL
        GROUP BY rs.contract_no
        """)
    java.util.Map<String, Object> selectContractInfoByFactId(@Param("factId") Long factId);

    /**
     * 按期间 + 合同号查询合同基本信息（调整单详情展示用）。
     *
     * @param period     归属期间
     * @param contractNo 合同号
     * @return 合同摘要；查不到返回 null
     */
    @Select("""
        SELECT COALESCE(rs.contract_no, MAX(rs.order_no)) AS "contractNo",
               MAX(rs.order_no) AS "orderNo",
               MAX(f.biz_type) AS "bizType",
               MAX(rs.raw_json ->> 'propertyAddress') AS "propertyAddress",
               MAX(COALESCE((rs.raw_json ->> 'signDate')::timestamp, f.business_date::timestamp)) AS "businessDate"
        FROM pj_perf_fact f
        JOIN pj_normalized_record nr ON nr.id = f.normalized_record_id
        JOIN pj_import_raw_signed rs ON rs.id = nr.raw_data_id
        WHERE f.fact_status = 'ACTIVE'
          AND f.period = #{period}
          AND (rs.contract_no = #{contractNo}
               OR (CASE WHEN f.biz_type IN ('一手房','房产金融','家装荐客')
                        THEN COALESCE(rs.order_no, rs.contract_no)
                        ELSE COALESCE(rs.contract_no, rs.order_no) END) = #{contractNo})
        GROUP BY rs.contract_no
        """)
    java.util.Map<String, Object> selectContractInfoByContractNo(
        @Param("period") String period, @Param("contractNo") String contractNo);

    /**
     * 按期间 + 合同号 + 事实口径查询该合同下全部有效明细（调整单详情展示用）。
     * <p>
     * 只查 ACTIVE 状态的事实，过滤掉已冲销/已替代的历史行。
     * 应收金额与实收金额按 source_key 交叉配对，与业绩明细页口径一致。
     *
     * @param period     归属期间
     * @param contractNo 合同号
     * @param factType   事实口径（PERF_EXPECT / PERF_REAL）
     * @return 明细列表
     */
    @Select("""
        SELECT f.id AS "factId",
               f.employee_id AS "employeeId",
               COALESCE(e.employee_code, f.employee_external_code) AS "employeeCode",
               e.employee_name AS "employeeName",
               CASE
                   WHEN array_length(string_to_array(d.ancestors, ','), 1) >= 3 THEN
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
               COALESCE(nr.role_type, f.role_type) AS "roleType",
               rs.role_name AS "roleName",
               f.share_ratio AS "shareRatio",
               (SELECT pe.performance_amount
                  FROM pj_perf_fact pe
                 WHERE pe.fact_status = 'ACTIVE'
                   AND pe.fact_type = CASE WHEN #{factType} = 'PERF_EXPECT' THEN 'PERF_REAL' ELSE 'PERF_EXPECT' END
                   AND pe.source_key = f.source_key
                 ORDER BY pe.id
                 LIMIT 1) AS "expectedAmount",
               f.performance_amount AS "amount",
               f.fact_status AS "factStatus"
        FROM pj_perf_fact f
        LEFT JOIN pj_people_employee e ON e.employee_id = f.employee_id
        LEFT JOIN sys_dept d ON d.dept_id = f.dept_id
        LEFT JOIN sys_dept p ON p.dept_id = d.parent_id
        LEFT JOIN sys_dept gp ON gp.dept_id = p.parent_id
        LEFT JOIN pj_normalized_record nr ON nr.id = f.normalized_record_id
        LEFT JOIN pj_import_raw_signed rs ON rs.id = nr.raw_data_id
        WHERE f.fact_status = 'ACTIVE'
          AND f.period = #{period}
          AND f.fact_type = #{factType}
          AND (rs.contract_no = #{contractNo}
               OR (CASE WHEN f.biz_type IN ('一手房','房产金融','家装荐客')
                        THEN COALESCE(rs.order_no, rs.contract_no)
                        ELSE COALESCE(rs.contract_no, rs.order_no) END) = #{contractNo})
        ORDER BY e.employee_name, d.dept_id, nr.role_type, f.id
        """)
    List<AdjustFactDetailDTO> selectAdjustFactDetails(@Param("period") String period,
                                                       @Param("contractNo") String contractNo,
                                                       @Param("factType") String factType);

    /**
     * 完整业绩查询（业务键维度聚合）。
     * <p>
     * 以业务键（一手房、房产金融、家装荐客按订单号，其余按合同号、合同号为空回退订单号）
     * + 期间为维度，聚合新签业绩、实收业绩、调整状态、实收审批状态、结佣状态。
     * 仅查 ACTIVE 事实；单据（调整/实收/结佣）按单据号或订单号匹配（兼容两种落库键）。
     * <p>
     * 性能结构（避免 8s+ 慢查询）：
     * <ol>
     *   <li>{@code contract_period}：按筛选条件圈定业务键及最新期间（全周期口径时为全部合同）；</li>
     *   <li>{@code reversed_expect}：一次性预聚合每个 source_key 最早一条 REVERSED 应收金额，
     *       替代原聚合内「每行事实一次相关子查询」；</li>
     *   <li>{@code fact_agg}：对圈定业务键的事实做一次 JOIN 聚合；</li>
     *   <li>调整/实收/结佣三类单据改为分页结果上的 LATERAL 连接（每合同每类 1 次索引探测），
     *       替代原「每行结果 10 个相关子查询」。</li>
     * </ol>
     *
     * @param period     归属期间（CTE 过滤：该期间有事实的合同才参与）
     * @param deptId     部门 ID（可选，含子部门）
     * @param keyword    关键字（可选：合同号/订单号/物业地址）
     * @param offset     偏移量
     * @param pageSize   每页条数
     * @return 合同维度业绩汇总列表
     */
    @Select("""
        <script>
        WITH contract_period AS (
            SELECT CASE WHEN f.biz_type IN ('一手房','房产金融','家装荐客')
                        THEN COALESCE(rs.order_no, rs.contract_no)
                        ELSE COALESCE(rs.contract_no, rs.order_no) END AS biz_key,
                   MAX(f.period) AS max_period
            FROM pj_perf_fact f
            JOIN pj_normalized_record nr ON nr.id = f.normalized_record_id
            JOIN pj_import_raw_signed rs ON rs.id = nr.raw_data_id
            WHERE
            <choose>
              <when test="factStatus == 'ALL'">
                f.fact_status IN ('ACTIVE', 'VOIDED')
              </when>
              <when test="factStatus != null and factStatus != ''">
                f.fact_status = #{factStatus}
              </when>
              <otherwise>
                f.fact_status = 'ACTIVE'
              </otherwise>
            </choose>
              AND CASE WHEN f.biz_type IN ('一手房','房产金融','家装荐客')
                       THEN COALESCE(rs.order_no, rs.contract_no)
                       ELSE COALESCE(rs.contract_no, rs.order_no) END IS NOT NULL
            <if test="period != null and period != ''">
              AND f.period = #{period}
            </if>
            <if test="deptId != null">
              AND (f.dept_id = #{deptId}
                   OR EXISTS (SELECT 1 FROM sys_dept sd WHERE sd.dept_id = f.dept_id
                              AND sd.ancestors LIKE CONCAT('%', #{deptId}, '%')))
            </if>
            <if test="keyword != null and keyword != ''">
              AND (rs.contract_no ILIKE CONCAT('%', #{keyword}::text, '%')
                OR rs.order_no ILIKE CONCAT('%', #{keyword}::text, '%')
                OR rs.raw_json ->> 'propertyAddress' ILIKE CONCAT('%', #{keyword}::text, '%'))
            </if>
            GROUP BY 1
        ),
        reversed_expect AS (
            SELECT DISTINCT ON (pf.source_key)
                   pf.source_key, pf.performance_amount
            FROM pj_perf_fact pf
            WHERE pf.fact_type = 'PERF_EXPECT'
              AND pf.fact_status = 'REVERSED'
            ORDER BY pf.source_key, pf.id ASC
        ),
        fact_agg AS (
            SELECT cp.biz_key AS "bizKey",
                   cp.max_period AS "period",
                   MAX(rs.contract_no) AS "contractNo",
                   MAX(rs.order_no) AS "orderNo",
                   MAX(f.biz_type) AS "bizType",
                   MAX(rs.raw_json ->> 'propertyAddress') AS "propertyAddress",
                   MAX(COALESCE((rs.raw_json ->> 'signDate')::timestamp, f.business_date::timestamp)) AS "signDate",
                   COALESCE(SUM(CASE WHEN f.fact_type = 'PERF_EXPECT' THEN f.performance_amount ELSE 0 END), 0) AS "expectAmount",
                   COALESCE(SUM(CASE WHEN f.fact_type = 'PERF_EXPECT'
                                     THEN COALESCE(re.performance_amount, f.performance_amount) ELSE 0 END), 0) AS "expectOriginalAmount",
                   COALESCE(SUM(CASE WHEN f.fact_type = 'PERF_REAL' THEN f.performance_amount ELSE 0 END), 0) AS "realAmount",
                   BOOL_OR(f.adjust_id IS NOT NULL) AS "hasAdjust",
                   COALESCE(SUM(CASE WHEN f.adjust_id IS NOT NULL AND f.fact_type = 'PERF_EXPECT'
                                     THEN f.performance_amount ELSE 0 END), 0) AS "adjustedAmount",
                   COUNT(DISTINCT f.employee_id) AS "employeeCount",
                   COUNT(*) AS "detailCount"
            FROM contract_period cp
            JOIN pj_perf_fact f ON
            <choose>
              <when test="factStatus == 'ALL'">
                f.fact_status IN ('ACTIVE', 'VOIDED')
              </when>
              <when test="factStatus != null and factStatus != ''">
                f.fact_status = #{factStatus}
              </when>
              <otherwise>
                f.fact_status = 'ACTIVE'
              </otherwise>
            </choose>
            JOIN pj_normalized_record nr ON nr.id = f.normalized_record_id
            JOIN pj_import_raw_signed rs ON rs.id = nr.raw_data_id
                AND CASE WHEN f.biz_type IN ('一手房','房产金融','家装荐客')
                         THEN COALESCE(rs.order_no, rs.contract_no)
                         ELSE COALESCE(rs.contract_no, rs.order_no) END = cp.biz_key
            LEFT JOIN reversed_expect re ON re.source_key = f.source_key
            GROUP BY cp.biz_key, cp.max_period
        )
        SELECT fa."period",
               fa."contractNo",
               fa."orderNo",
               fa."bizType",
               fa."propertyAddress",
               fa."signDate",
               fa."expectAmount",
               fa."expectOriginalAmount",
               fa."realAmount",
               fa."hasAdjust",
               fa."adjustedAmount",
               fa."employeeCount",
               fa."detailCount",
               <choose>
                 <when test="factStatus == 'ALL'">'ALL'</when>
                 <when test="factStatus != null and factStatus != ''">#{factStatus}</when>
                 <otherwise>'ACTIVE'</otherwise>
               </choose> AS "factStatus",
               la.status AS "adjustStatus",
               la.adjust_no AS "adjustNo",
               la.adjust_type AS "adjustType",
               lr.status AS "receivedStatus",
               lr.apply_no AS "receivedApplyNo",
               lr.expected_amount AS "receivedExpectedAmount",
               lr.received_amount AS "receivedRealAmount",
               lc.status AS "commissionStatus",
               lc.apply_no AS "commissionApplyNo",
               lc.total_amount AS "commissionAmount"
        FROM fact_agg fa
        LEFT JOIN LATERAL (
            SELECT pa.status, pa.adjust_no, pa.adjust_type
            FROM pj_perf_adjust pa
            WHERE pa.period = fa."period"
              AND pa.contract_no IN (fa."contractNo", fa."orderNo")
            ORDER BY pa.id DESC
            LIMIT 1
        ) la ON TRUE
        LEFT JOIN LATERAL (
            SELECT ra.status, ra.apply_no, ra.expected_amount, ra.received_amount
            FROM pj_perf_received_apply ra
            WHERE ra.period = fa."period"
              AND ra.contract_no IN (fa."contractNo", fa."orderNo")
            ORDER BY ra.id DESC
            LIMIT 1
        ) lr ON TRUE
        LEFT JOIN LATERAL (
            SELECT ca.status, ca.apply_no, ca.total_amount
            FROM pj_commission_application ca
            WHERE ca.period = fa."period"
              AND ca.contract_no IN (fa."contractNo", fa."orderNo")
            ORDER BY ca.id DESC
            LIMIT 1
        ) lc ON TRUE
        ORDER BY fa."signDate" DESC, fa."contractNo"
        LIMIT #{pageSize} OFFSET #{offset}
        </script>
        """)
    List<PerformanceFactSearchDTO> selectFactSearchByContract(
            @Param("period") String period,
            @Param("deptId") Long deptId,
            @Param("keyword") String keyword,
            @Param("factStatus") String factStatus,
            @Param("offset") long offset,
            @Param("pageSize") int pageSize);

    /**
     * 完整业绩查询的合同数（分页 total，按业务键去重）。
     */
    @Select("""
        <script>
        SELECT COUNT(DISTINCT CASE WHEN f.biz_type IN ('一手房','房产金融','家装荐客')
                                    THEN COALESCE(rs.order_no, rs.contract_no)
                                    ELSE COALESCE(rs.contract_no, rs.order_no) END)
        FROM pj_perf_fact f
        JOIN pj_normalized_record nr ON nr.id = f.normalized_record_id
        JOIN pj_import_raw_signed rs ON rs.id = nr.raw_data_id
        WHERE
        <choose>
          <when test="factStatus == 'ALL'">
            f.fact_status IN ('ACTIVE', 'VOIDED')
          </when>
          <when test="factStatus != null and factStatus != ''">
            f.fact_status = #{factStatus}
          </when>
          <otherwise>
            f.fact_status = 'ACTIVE'
          </otherwise>
        </choose>
          AND CASE WHEN f.biz_type IN ('一手房','房产金融','家装荐客')
                    THEN COALESCE(rs.order_no, rs.contract_no)
                    ELSE COALESCE(rs.contract_no, rs.order_no) END IS NOT NULL
          <if test="period != null and period != ''">
            AND f.period = #{period}
          </if>
          <if test="deptId != null">
            AND (f.dept_id = #{deptId}
                 OR EXISTS (SELECT 1 FROM sys_dept sd WHERE sd.dept_id = f.dept_id
                            AND sd.ancestors LIKE CONCAT('%', #{deptId}, '%')))
          </if>
          <if test="keyword != null and keyword != ''">
            AND (
              rs.contract_no ILIKE CONCAT('%', #{keyword}::text, '%')
              OR rs.order_no ILIKE CONCAT('%', #{keyword}::text, '%')
              OR rs.raw_json ->> 'propertyAddress' ILIKE CONCAT('%', #{keyword}::text, '%')
            )
          </if>
        </script>
        """)
    long countFactSearchByContract(
            @Param("period") String period,
            @Param("deptId") Long deptId,
            @Param("keyword") String keyword,
            @Param("factStatus") String factStatus);

    /**
     * 完整业绩查询·按业务键查询合同下明细（查看详情弹窗数据源）。
     * <p>
     * 以 PERF_EXPECT（新签/应收）ACTIVE 事实为基准行，按 source_key 配对同业务的
     * PERF_REAL（实收）金额，一行同时展示应收/实收双口径；含该业务键全部期间
     * （与 {@link #selectFactSearchByContract} 的合同全周期聚合口径一致）。
     * 业务键口径：一手房、房产金融、家装荐客传订单号，其余传合同号（空则订单号）。
     *
     * @param bizNo 业务键（列表行展示的合同号/订单号）
     * @return 该业务键下全部明细行（按期间倒序、姓名、角色排序）
     */
    @Select("""
        SELECT f.id AS "factId",
               f.fact_status AS "factStatus",
               f.period AS "period",
               f.employee_id AS "employeeId",
               COALESCE(e.employee_code, f.employee_external_code) AS "employeeCode",
               e.employee_name AS "employeeName",
               CASE
                   WHEN array_length(string_to_array(d.ancestors, ','), 1) >= 3 THEN
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
               COALESCE(nr.role_type, f.role_type) AS "roleType",
               rs.role_name AS "roleName",
               f.share_ratio AS "shareRatio",
               COALESCE((rs.raw_json ->> 'signDate')::timestamp, f.business_date::timestamp) AS "businessDate",
               f.performance_amount AS "expectAmount",
               COALESCE(
                 (SELECT pf.performance_amount FROM pj_perf_fact pf
                  WHERE pf.source_key = f.source_key
                    AND pf.fact_type = 'PERF_EXPECT'
                    AND pf.fact_status = 'REVERSED'
                  ORDER BY pf.id ASC LIMIT 1),
                 f.performance_amount
               ) AS "originalExpectAmount",
               COALESCE(
                 (SELECT pr.performance_amount FROM pj_perf_fact pr
                  WHERE pr.source_key = f.source_key
                    AND pr.fact_type = 'PERF_REAL'
                    AND pr.fact_status = 'ACTIVE'
                  ORDER BY pr.id DESC LIMIT 1),
                 0
               ) AS "realAmount",
               (ci.id IS NOT NULL) AS "settled",
               ca.lock_time AS "settleDate"
        FROM pj_perf_fact f
        LEFT JOIN pj_people_employee e ON e.employee_id = f.employee_id
        LEFT JOIN sys_dept d ON d.dept_id = f.dept_id
        LEFT JOIN sys_dept p ON p.dept_id = d.parent_id
        LEFT JOIN sys_dept gp ON gp.dept_id = p.parent_id
        LEFT JOIN pj_normalized_record nr ON nr.id = f.normalized_record_id
        LEFT JOIN pj_import_raw_signed rs ON rs.id = nr.raw_data_id
        LEFT JOIN pj_commission_item ci ON ci.performance_fact_id = f.id
                                       AND ci.status <> 'REVERSED'
        LEFT JOIN pj_commission_application ca ON ca.id = ci.application_id
                                              AND ca.status IN ('APPROVED', 'LOCKED', 'CLOSED')
        WHERE f.fact_status IN ('ACTIVE', 'VOIDED')
          AND f.fact_type = 'PERF_EXPECT'
          AND (CASE WHEN f.biz_type IN ('一手房','房产金融','家装荐客')
                    THEN COALESCE(rs.order_no, rs.contract_no)
                    ELSE COALESCE(rs.contract_no, rs.order_no) END) = #{bizNo}
        ORDER BY f.period DESC, e.employee_name, d.dept_id, nr.role_type, f.id
        """)
    List<PerformanceSearchDetailDTO> selectSearchDetailRows(@Param("bizNo") String bizNo);
}

