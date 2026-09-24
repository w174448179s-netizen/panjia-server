package com.panjia.performance.mapper;

import com.panjia.contracts.dto.PerformanceContractSummaryDTO;
import com.panjia.contracts.dto.PerformanceFactSummaryDTO;
import com.panjia.performance.domain.PerformanceFact;
import com.panjia.performance.domain.vo.AdjustFactDetailVo;
import com.panjia.performance.domain.vo.PerformanceFactSearchVo;
import com.panjia.performance.domain.vo.PerformanceManageContractVo;
import com.panjia.performance.domain.vo.PerformanceManageVo;
import com.panjia.performance.domain.vo.PerformanceSearchDetailVo;
import com.panjia.performance.domain.vo.ReceivedContractMetricsVo;
import com.panjia.performance.domain.vo.ReceivedFactDetailVo;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 业绩事实 Mapper。
 */
@Mapper
public interface PerformanceFactMapper extends BaseMapperPlus<PerformanceFact, PerformanceFact> {

    /**
     * 全局汇总（与过滤条件一致，跨所有页）：明细数、合同数、金额合计。
     */
    @Select("""
        <script>
        SELECT COUNT(*) AS "detailCount",
               COUNT(DISTINCT COALESCE(f.order_no, f.contract_no)) AS "contractCount",
               COUNT(DISTINCT f.employee_id) AS "employeeCount",
               COALESCE(SUM(f.performance_amount), 0) AS "totalAmount"
        FROM pj_perf_fact f
        WHERE
        <choose>
          <when test="factStatus == 'ALL'">f.fact_status IN ('ACTIVE', 'VOIDED')</when>
          <when test="factStatus != null and factStatus != ''">f.fact_status = #{factStatus}</when>
          <otherwise>f.fact_status = 'ACTIVE'</otherwise>
        </choose>
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
          <if test="keyword != null and keyword != ''">
            AND (
              f.contract_no ILIKE CONCAT('%', #{keyword}::text, '%')
              OR f.order_no ILIKE CONCAT('%', #{keyword}::text, '%')
              OR f.property_address ILIKE CONCAT('%', #{keyword}::text, '%')
              OR f.role_type ILIKE CONCAT('%', #{keyword}::text, '%')
            )
          </if>
          <if test="employeeId != null">
            AND f.employee_id = #{employeeId}
          </if>
          <if test="selfEmployeeId != null">
            AND f.employee_id = #{selfEmployeeId}
          </if>
        </script>
        """)
    java.util.Map<String, Object> selectManageSummary(@Param("period") String period,
                                                      @Param("factType") String factType,
                                                      @Param("deptId") Long deptId,
                                                      @Param("employeeId") Long employeeId,
                                                      @Param("bizType") String bizType,
                                                      @Param("keyword") String keyword,
                                                      @Param("factStatus") String factStatus,
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
     * 业务键（订单号 / 合同号）口径——贝壳原始行中 order_no 与 contract_no 严格 1:1
     * （实测双向 0 冲突、按订单号分组与按业务类型 CASE 分组组数完全相等：238 = 238、order_no 无 NULL），
     * 故二者互为等价业务键，`CASE WHEN biz_type IN ('一手房','房产金融','家装荐客')` 的分派是冗余的。
     *
     * 【已统一】实收建单链路（2026-09-22 改动）：
     * - 分组维度 selectBatchReceivedContractGroups：`GROUP BY rs.order_no`，CASE 已删；
     * - 单据匹配 selectBatchUnboundRealFacts / selectExpectSumsByBizKeys：
     *   纯 `f.order_no = 键`，不再 OR contract_no（订单号与合同号 1:1，简化为单一键）。
     *
     * 【未统一】本文件其余 21 处 CASE 分派（业绩管理列表 / 作废恢复 / 调整 / 结佣 / 业绩查询，见
     * line 318 起至 1741）保持原样：它们的调用方可能回传「按业务类型决定的展示键」（前端
     * src/utils/panjiaBiz.ts 的 resolveBizNo），单改 SQL 一侧会漏匹配，须前后端一起动。
     * 统一时：分组维度可用 `COALESCE(rs.order_no, rs.contract_no)`；对传入键做匹配必须用
     * `键 = contract_no OR 键 = order_no`（不能用单一列的 COALESCE，否则按展示键回传时漏命中）。
     */

    /**
     * 业绩管理合同维度分页（以 COALESCE(order_no, contract_no) 为分页维度）。
     * <p>
     * 每业务键一行：合同号/订单号/业务类型/房源地址/签约日期/合同金额合计/涉及人数/明细数/未结算数。
     * 订单号与合同号 1:1，统一用 COALESCE(order_no, contract_no) 分组。
     * 合同下的签约人明细由 {@link #selectManageListByContractNos} 懒加载。
     * <p>
     * originalAmount 已移除（如需调整前金额，另查）；pj_people_employee JOIN 已移除
     * （UI 改为按 employeeId 查询，keyword 不再搜员工工号/姓名）；
     * pj_commission_item LATERAL JOIN 仅 PERF_REAL 时生效（结佣只关联实收事实）。
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
        SELECT MAX(f.contract_no) AS "contractNo",
               MAX(f.order_no) AS "orderNo",
               MAX(f.biz_type) AS "bizType",
               MAX(f.property_address) AS "propertyAddress",
               MAX(f.business_date) AS "businessDate",
               COALESCE(SUM(f.performance_amount), 0) AS "amount",
               COUNT(DISTINCT f.employee_id) AS "employeeCount",
               COUNT(*) AS "detailCount",
               CASE WHEN BOOL_OR(f.fact_status = 'ACTIVE') THEN 'ACTIVE' ELSE 'VOIDED' END AS "factStatus"
        FROM pj_perf_fact f
        WHERE
        <choose>
          <when test="factStatus == 'ALL'">f.fact_status IN ('ACTIVE', 'VOIDED')</when>
          <when test="factStatus != null and factStatus != ''">f.fact_status = #{factStatus}</when>
          <otherwise>f.fact_status = 'ACTIVE'</otherwise>
        </choose>
          AND f.period = #{period}
          AND f.fact_type = #{factType}
          AND COALESCE(f.order_no, f.contract_no) IS NOT NULL
          <if test="deptId != null">
            AND (f.dept_id = #{deptId}
                 OR EXISTS (SELECT 1 FROM sys_dept sd WHERE sd.dept_id = f.dept_id
                            AND sd.ancestors LIKE CONCAT('%', #{deptId}, '%')))
          </if>
          <if test="bizType != null and bizType != ''">
            AND f.biz_type = #{bizType}
          </if>
          <if test="keyword != null and keyword != ''">
            AND (
              f.contract_no ILIKE CONCAT('%', #{keyword}::text, '%')
              OR f.order_no ILIKE CONCAT('%', #{keyword}::text, '%')
              OR f.property_address ILIKE CONCAT('%', #{keyword}::text, '%')
              OR f.role_type ILIKE CONCAT('%', #{keyword}::text, '%')
            )
          </if>
          <if test="employeeId != null">
            AND f.employee_id = #{employeeId}
          </if>
          <if test="selfEmployeeId != null">
            AND f.employee_id = #{selfEmployeeId}
          </if>
        GROUP BY COALESCE(f.order_no, f.contract_no)
        ORDER BY "businessDate" DESC, "contractNo"
        LIMIT #{pageSize} OFFSET #{offset}
        </script>
        """)
    List<PerformanceManageContractVo> selectManagePageContracts(@Param("period") String period,
                                            @Param("factType") String factType,
                                            @Param("deptId") Long deptId,
                                            @Param("employeeId") Long employeeId,
                                            @Param("bizType") String bizType,
                                            @Param("keyword") String keyword,
                                            @Param("factStatus") String factStatus,
                                            @Param("selfEmployeeId") Long selfEmployeeId,
                                            @Param("offset") long offset,
                                            @Param("pageSize") int pageSize);

    /**
     * 统计符合条件的合同数（分页 total，按 COALESCE(order_no, contract_no) 去重）。
     * 参数语义同 {@link #selectManagePageContracts}。
     */
    @Select("""
        <script>
        SELECT COUNT(DISTINCT COALESCE(f.order_no, f.contract_no))
        FROM pj_perf_fact f
        WHERE
        <choose>
          <when test="factStatus == 'ALL'">f.fact_status IN ('ACTIVE', 'VOIDED')</when>
          <when test="factStatus != null and factStatus != ''">f.fact_status = #{factStatus}</when>
          <otherwise>f.fact_status = 'ACTIVE'</otherwise>
        </choose>
          AND f.period = #{period}
          AND f.fact_type = #{factType}
          AND COALESCE(f.order_no, f.contract_no) IS NOT NULL
          <if test="deptId != null">
            AND (f.dept_id = #{deptId}
                 OR EXISTS (SELECT 1 FROM sys_dept sd WHERE sd.dept_id = f.dept_id
                            AND sd.ancestors LIKE CONCAT('%', #{deptId}, '%')))
          </if>
          <if test="bizType != null and bizType != ''">
            AND f.biz_type = #{bizType}
          </if>
          <if test="keyword != null and keyword != ''">
            AND (
              f.contract_no ILIKE CONCAT('%', #{keyword}::text, '%')
              OR f.order_no ILIKE CONCAT('%', #{keyword}::text, '%')
              OR f.property_address ILIKE CONCAT('%', #{keyword}::text, '%')
              OR f.role_type ILIKE CONCAT('%', #{keyword}::text, '%')
            )
          </if>
          <if test="employeeId != null">
            AND f.employee_id = #{employeeId}
          </if>
          <if test="selfEmployeeId != null">
            AND f.employee_id = #{selfEmployeeId}
          </if>
        </script>
        """)
    long countManageContracts(@Param("period") String period,
                              @Param("factType") String factType,
                              @Param("deptId") Long deptId,
                              @Param("employeeId") Long employeeId,
                              @Param("bizType") String bizType,
                              @Param("keyword") String keyword,
                              @Param("factStatus") String factStatus,
                              @Param("selfEmployeeId") Long selfEmployeeId);

    /**
     * 按业务键集合查询业绩明细（合同维度树表懒加载数据源）。
     * <p>
     * 单表查询 pj_perf_fact，employeeName/employeeCode/deptPath/originalAmount/settled/settleDate
     * 由 Service 层批量补充查询填充，避免 CTE + 5 个 JOIN 的复杂执行计划。
     * 键口径与 {@link #selectManagePageContracts} 一致。
     *
     * @param contractNos 业务键集合（不能为空；列表行展示的合同号/订单号）
     */
    @Select("""
        <script>
        SELECT f.id,
               f.fact_status AS "factStatus",
               f.fact_type AS factType,
               f.period,
               f.business_date AS businessDate,
               f.order_no AS orderNo,
               f.contract_no AS contractNo,
               f.biz_type AS bizType,
               f.property_address AS propertyAddress,
               f.fee_item AS feeItem,
               f.employee_id AS employeeId,
               f.role_type AS roleType,
               f.role_name AS roleName,
               f.share_ratio AS shareRatio,
               f.performance_amount AS amount,
               f.source_key AS sourceKey
        FROM pj_perf_fact f
        WHERE f.fact_status IN ('ACTIVE', 'VOIDED')
          AND f.period = #{period}
          AND f.fact_type = #{factType}
          AND CASE WHEN f.biz_type IN ('一手房','房产金融','家装荐客')
                   THEN COALESCE(f.order_no, f.contract_no)
                   ELSE COALESCE(f.contract_no, f.order_no) END IN
          <foreach collection="contractNos" item="cn" open="(" separator="," close=")">#{cn}</foreach>
        ORDER BY f.contract_no, f.employee_id, businessDate, f.role_type
        </script>
        """)
    List<PerformanceManageVo> selectManageListByContractNos(@Param("period") String period,
                                                             @Param("factType") String factType,
                                                             @Param("contractNos") List<String> contractNos);

    /**
     * 查询指定合同号/订单号下全部 ACTIVE 业绩事实（合同级调整 / 实收建单用）。
     * <p>
     * 通过 normalized_record → raw_signed 关联定位同组的所有明细事实。
     * 匹配口径：传入键命中 {@code contract_no} 或 {@code order_no} 任一即可——
     * 二者在贝壳原始行中严格 1:1（实测 0 冲突），故「命中任一」与按业务类型取键等价，
     * 且同时兼容两类历史落库键（实收审批单早期把订单号写进 contract_no 的一手房单据）。
     *
     * @param period     归属期间
     * @param factType   事实口径
     * @param contractNo 合同号或订单号（业务键）
     * @return 同组全部 ACTIVE 事实列表
     */
    @Select("""
        SELECT f.*
        FROM pj_perf_fact f
        WHERE f.fact_status = 'ACTIVE'
          AND f.period = #{period}
          AND f.fact_type = #{factType}
          AND (f.contract_no = #{contractNo} OR f.order_no = #{contractNo})
        ORDER BY f.id
        """)
    List<PerformanceFact> selectActiveFactsByContractNo(@Param("period") String period,
                                                         @Param("factType") String factType,
                                                         @Param("contractNo") String contractNo);

    /**
     * 查询指定合同号下全部已作废（VOIDED）业绩事实（合同级恢复用）。
     * <p>
     * 作废不改变 period，故仍按原期间定位；合同号匹配口径同 {@link #selectActiveFactsByContractNo}。
     *
     * @param period     归属期间
     * @param factType   事实口径
     * @param contractNo 合同号
     * @return 该合同下全部 VOIDED 事实列表
     */
    @Select("""
        SELECT f.*
        FROM pj_perf_fact f
        WHERE f.fact_status = 'VOIDED'
          AND f.period = #{period}
          AND f.fact_type = #{factType}
          AND (f.contract_no = #{contractNo} OR f.order_no = #{contractNo})
        ORDER BY f.id
        """)
    List<PerformanceFact> selectVoidedFactsByContractNo(@Param("period") String period,
                                                         @Param("factType") String factType,
                                                         @Param("contractNo") String contractNo);

    /**
     * 统计指定事实同合同（同期间/口径/业务键）下已作废（VOIDED）事实数量。
     * <p>
     * 用于明细级调整前的合同状态校验：合同存在已作废明细时禁止调整。
     * 合同匹配口径同 {@link #selectActiveFactsByContractNo}（按业务类型取合同号/订单号）。
     *
     * @param factId 主事实 ID
     * @return 同合同 VOIDED 事实数量
     */
    @Select("""
        SELECT COUNT(*)
        FROM pj_perf_fact vf
        JOIN pj_perf_fact mf ON mf.id = #{factId}
        WHERE vf.fact_status = 'VOIDED'
          AND vf.period = mf.period
          AND vf.fact_type = mf.fact_type
          AND COALESCE(vf.contract_no, vf.order_no) = COALESCE(mf.contract_no, mf.order_no)
        """)
    long countVoidedSiblingsByFactId(@Param("factId") Long factId);

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
               f.contract_no AS "contractNo",
               f.order_no AS "orderNo",
               f.property_address AS "propertyAddress",
               f.share_ratio AS "shareRatio",
               CAST(f.business_date AS VARCHAR) AS "signDate"
        FROM pj_perf_fact f
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
               f.contract_no AS "contractNo",
               f.order_no AS "orderNo",
               f.property_address AS "propertyAddress",
               f.share_ratio AS "shareRatio"
        FROM pj_perf_fact f
        LEFT JOIN pj_perf_received_apply ra ON ra.id = f.received_apply_id
        WHERE f.fact_status = 'ACTIVE'
          AND f.period = #{period}
          AND f.fact_type = #{factType}
          AND (f.contract_no = #{contractNo} OR f.order_no = #{contractNo})
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
     * <p>
     * 应收同时给出 originalExpectedAmount（调整前：同 sourceKey 最早一条 REVERSED 的
     * PERF_EXPECT 金额，无调整时回退为当前 ACTIVE 金额），与「合同业绩明细」页
     * originalAmount 同口径，供前端展示「原值 → 调整后值」。
     *
     * @param period     归属期间
     * @param contractNo 合同号
     * @return 每人实收明细行
     */
    @Select("""
        <script>
        WITH src_keys AS (
            SELECT DISTINCT source_key
            FROM pj_perf_fact
            WHERE fact_status = 'ACTIVE'
              AND period = #{period}
              AND fact_type = 'PERF_REAL'
              AND (contract_no = #{contractNo} OR order_no = #{contractNo})
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
        ),
        reversed_real AS (
            SELECT DISTINCT ON (sk.source_key) sk.source_key, pr.performance_amount
            FROM src_keys sk
            JOIN pj_perf_fact pr ON pr.source_key = sk.source_key
               AND pr.fact_status = 'REVERSED' AND pr.fact_type = 'PERF_REAL'
            ORDER BY sk.source_key, pr.id ASC
        )
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
               f.role_type AS "roleType",
               f.role_name AS "roleName",
               f.share_ratio AS "shareRatio",
               ae.performance_amount AS "expectedAmount",
               COALESCE(re.performance_amount, ae.performance_amount) AS "originalExpectedAmount",
               (re.source_key IS NOT NULL) AS "expectedAdjusted",
               f.performance_amount AS "amount",
               COALESCE(rr.performance_amount, f.performance_amount) AS "originalAmount",
               (rr.source_key IS NOT NULL) AS "receivedAdjusted"
        FROM pj_perf_fact f
        LEFT JOIN active_expect ae ON ae.source_key = f.source_key
        LEFT JOIN reversed_expect re ON re.source_key = f.source_key
        LEFT JOIN reversed_real rr ON rr.source_key = f.source_key
        LEFT JOIN pj_people_employee e ON e.employee_id = f.employee_id
        LEFT JOIN sys_dept d ON d.dept_id = f.dept_id
        LEFT JOIN sys_dept p ON p.dept_id = d.parent_id
        LEFT JOIN sys_dept gp ON gp.dept_id = p.parent_id
        WHERE f.fact_status = 'ACTIVE'
          AND f.period = #{period}
          AND f.fact_type = 'PERF_REAL'
          AND (f.contract_no = #{contractNo} OR f.order_no = #{contractNo})
        ORDER BY e.employee_name, d.dept_id, f.role_type, f.id
        </script>
        """)
    List<ReceivedFactDetailVo> selectReceivedFactDetails(@Param("period") String period,
                                                           @Param("contractNo") String contractNo);

    /**
     * 按期间 + 业务键集合查询实收明细列表的补充字段（涉及人数、应收合计），每传入键一行。
     * <p>
     * <b>业务类型已落库</b>到 {@code pj_perf_received_apply.biz_type}（建单时快照），
     * 列表页不再依赖本查询回填 bizType；此处仍带出 bizType 便于口径核对。
     * 应收合计取 ACTIVE PERF_EXPECT（含已生效调整），使列表「新签业绩」显示调整后金额；
     * 实收合计取 ACTIVE PERF_REAL，并额外给出 originalReceivedAmount（按当前 ACTIVE 事实
     * sourceKey 链取最早一条事实金额求和，含已生效结佣调整前原值），使列表「实收业绩」
     * 可展示「原值 → 调整后值」。
     * 匹配口径：传入键命中 {@code contract_no} 或 {@code order_no} 任一即可（二者 1:1，
     * 兼容早期把订单号写进 contract_no 的一手房单据）。
     *
     * @param period      归属期间
     * @param contractNos 单据上的合同号/订单号集合（不可为空，调用方需先过滤）
     * @return 每键一行的业务类型、涉及人数、实收/应收合计与实收调整前合计
     */
    @Select("""
        <script>
        SELECT k.key AS "contractNo",
               MAX(f.biz_type) AS "bizType",
               COUNT(DISTINCT f.employee_id) AS "employeeCount",
               COALESCE(SUM(f.performance_amount), 0) AS "receivedAmount",
               COALESCE((
                   SELECT SUM(e.performance_amount)
                   FROM pj_perf_fact e
                   WHERE e.fact_status = 'ACTIVE' AND e.fact_type = 'PERF_EXPECT'
                     AND e.period = #{period}
                     AND (e.contract_no = k.key OR e.order_no = k.key)
               ), 0) AS "expectedAmount",
               COALESCE((
                   SELECT SUM(o.performance_amount)
                   FROM (
                       SELECT DISTINCT ON (a.source_key) a.source_key, a.performance_amount
                       FROM pj_perf_fact a
                       WHERE a.fact_type = 'PERF_REAL'
                         AND a.period = #{period}
                         AND (a.contract_no = k.key OR a.order_no = k.key)
                         AND a.source_key IN (
                             SELECT b.source_key
                             FROM pj_perf_fact b
                             WHERE b.fact_status = 'ACTIVE' AND b.fact_type = 'PERF_REAL'
                               AND b.period = #{period}
                               AND (b.contract_no = k.key OR b.order_no = k.key)
                         )
                       ORDER BY a.source_key, a.id ASC
                   ) o
               ), 0) AS "originalReceivedAmount"
        FROM (VALUES
          <foreach collection="contractNos" item="cn" separator=",">(#{cn})</foreach>
        ) AS k(key)
        JOIN pj_perf_fact f ON f.fact_status = 'ACTIVE'
                           AND f.fact_type = 'PERF_REAL'
                           AND f.period = #{period}
        WHERE (f.contract_no = k.key OR f.order_no = k.key)
        GROUP BY k.key
        </script>
        """)
    List<ReceivedContractMetricsVo> selectReceivedContractMetrics(
        @Param("period") String period,
        @Param("contractNos") Collection<String> contractNos);

    /**
     * 批量查合同维度「调整前」事实金额合计（结佣明细列表展示「原值 → 调整后值」用）。
     * <p>
     * 以各业务键当前 ACTIVE 事实的 sourceKey 集合为准，沿事实链（同 sourceKey，
     * 不区分 fact_status）取 id 最早一条事实金额求和——未调整时最早一条即 ACTIVE 自身，
     * 已调整（新签调整 / 结佣调整 supersede 均保留 sourceKey）时为最早 REVERSED 原值。
     *
     * @param period   归属期间
     * @param factType 事实口径
     * @param keys     合同号/订单号业务键集合（不可为空）
     * @return 每行 bizKey / originalAmount
     */
    @Select("""
        <script>
        WITH sk AS (
            SELECT DISTINCT k.key, f.source_key
            FROM (VALUES
              <foreach collection="keys" item="bk" separator=",">(#{bk})</foreach>
            ) AS k(key)
            JOIN pj_perf_fact f ON f.fact_status = 'ACTIVE'
                               AND f.fact_type = #{factType}
                               AND f.period = #{period}
                               AND (f.contract_no = k.key OR f.order_no = k.key)
        ),
        orig AS (
            SELECT DISTINCT ON (sk.source_key) sk.source_key, x.performance_amount AS amt
            FROM sk
            JOIN pj_perf_fact x ON x.source_key = sk.source_key
                               AND x.fact_type = #{factType}
                               AND x.period = #{period}
            ORDER BY sk.source_key, x.id ASC
        )
        SELECT sk.key AS "bizKey",
               COALESCE(SUM(orig.amt), 0) AS "originalAmount"
        FROM sk
        LEFT JOIN orig ON orig.source_key = sk.source_key
        GROUP BY sk.key
        </script>
        """)
    List<Map<String, Object>> selectOriginalFactAmountsByKeys(@Param("period") String period,
                                                               @Param("factType") String factType,
                                                               @Param("keys") Collection<String> keys);

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
                   WHERE e.fact_status = 'ACTIVE' AND e.fact_type = 'PERF_EXPECT'
                     AND e.period = #{period}
                     AND (CASE WHEN e.biz_type IN ('一手房','房产金融','家装荐客')
                               THEN COALESCE(e.order_no, e.contract_no)
                               ELSE COALESCE(e.contract_no, e.order_no) END) = s.biz_key
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
                        THEN COALESCE(f.order_no, f.contract_no)
                        ELSE COALESCE(f.contract_no, f.order_no) END AS biz_key,
                   f.order_no,
                   f.biz_type,
                   f.property_address,
                   f.business_date,
                   f.performance_amount AS amount,
                   ra.status AS ra_status,
                   ra.id AS ra_id,
                   f.employee_id
            FROM pj_perf_fact f
            LEFT JOIN pj_perf_received_apply ra ON ra.id = f.received_apply_id
            WHERE f.fact_status = 'ACTIVE'
              AND f.period = #{period}
              AND f.fact_type = #{factType}
              AND CASE WHEN f.biz_type IN ('一手房','房产金融','家装荐客')
                       THEN COALESCE(f.order_no, f.contract_no)
                       ELSE COALESCE(f.contract_no, f.order_no) END IS NOT NULL
            <if test="deptId != null">
              AND (f.dept_id = #{deptId}
                   OR EXISTS (SELECT 1 FROM sys_dept sd WHERE sd.dept_id = f.dept_id
                              AND sd.ancestors LIKE CONCAT('%', #{deptId}, '%')))
            </if>
            <if test="employeeId != null">
              AND f.employee_id = #{employeeId}
            </if>
        ) s
        GROUP BY s.biz_key
        ORDER BY MAX(s.business_date) DESC, s.biz_key
        </script>
        """)
    List<PerformanceContractSummaryDTO> selectContractSummaries(@Param("period") String period,
                                                                 @Param("factType") String factType,
                                                                 @Param("deptId") Long deptId,
                                                                 @Param("employeeId") Long employeeId);

    /**
     * 按导入批次聚合「实收业绩订单组」（实收审批单自动建单用，§2.1）。
     * <p>
     * 仅取本批新建、ACTIVE、尚未挂实收审批单（received_apply_id IS NULL）的 PERF_REAL 事实，
     * <b>按订单号聚合</b>：实收合计、快照字段、业务类型、明细条数。
     * <p>
     * 订单号即业务键：贝壳原始行中 {@code order_no} 与 {@code contract_no} 严格 1:1
     * （实测 0 冲突、238 组全等），故无需按业务类型做 CASE 分派——统一按订单号聚合，
     * 结果与旧的「一手房/房产金融/家装荐客取订单号、其余取合同号」口径完全等价。
     *
     * @param batchId 导入批次 ID
     * @param period  归属期间
     * @return 订单聚合组列表（orderNo = 业务键）
     */
    @Select("""
        SELECT MAX(s.contract_no) AS "contractNo",
               s.order_no AS "orderNo",
               MAX(s.property_address) AS "propertyAddress",
               MAX(s.business_date) AS "businessDate",
               MAX(s.biz_type) AS "bizType",
               COALESCE(SUM(s.amount), 0) AS "receivedAmount",
               COUNT(*) AS "itemCount"
        FROM (
            SELECT f.order_no,
                   f.contract_no,
                   f.biz_type,
                   f.property_address,
                   f.business_date,
                   f.performance_amount AS amount
            FROM pj_perf_fact f
            WHERE f.fact_status = 'ACTIVE'
              AND f.fact_type = 'PERF_REAL'
              AND f.batch_id = #{batchId}
              AND f.period = #{period}
              AND f.received_apply_id IS NULL
              AND f.order_no IS NOT NULL
        ) s
        GROUP BY s.order_no
        HAVING COALESCE(SUM(s.amount), 0) <> 0
        ORDER BY s.order_no
        """)
    List<com.panjia.performance.dto.ReceivedContractGroupDTO> selectBatchReceivedContractGroups(
        @Param("batchId") Long batchId, @Param("period") String period);

    /**
     * 一次查询批次内全部待绑定的非零 ACTIVE 实收事实行（自动建单性能优化用）。
     * <p>
     * 纯订单号匹配（{@code f.order_no = 键}），每个 factId 唯一归属一个订单号，
     * 无需服务层竞争裁决。
     *
     * @param batchId 导入批次 ID
     * @param period  归属期间
     * @param bizKeys 本批次聚合出的业务键（订单号）
     * @return 待绑定事实行（received_apply_id 为空、金额非 0）
     */
    @Select("""
        <script>
        SELECT f.id AS "factId",
               kv.biz_key AS "bizKey",
               f.dept_id AS "deptId",
               f.performance_amount AS "amount"
        FROM pj_perf_fact f
        JOIN (VALUES
        <foreach collection="bizKeys" item="k" separator=",">(CAST(#{k} AS text))</foreach>
        ) kv(biz_key)
          ON f.order_no = kv.biz_key
        WHERE f.fact_status = 'ACTIVE'
          AND f.fact_type = 'PERF_REAL'
          AND f.batch_id = #{batchId}
          AND f.period = #{period}
          AND f.received_apply_id IS NULL
          AND f.performance_amount IS NOT NULL
          AND f.performance_amount &lt;&gt; 0
        ORDER BY kv.biz_key, f.id
        </script>
        """)
    List<com.panjia.performance.dto.BatchFactBindRow> selectBatchUnboundRealFacts(
        @Param("batchId") Long batchId, @Param("period") String period,
        @Param("bizKeys") List<String> bizKeys);

    /**
     * 批量聚合多个业务键的应收业绩（PERF_EXPECT）合计（自动建单性能优化用）。
     * <p>
     * 纯订单号匹配，一条事实对一个键只计一次。
     * 返回行复用 {@link com.panjia.performance.dto.BatchFactBindRow}：
     * bizKey=业务键、amount=应收合计（factId/deptId 为空）。
     *
     * @param period  归属期间
     * @param bizKeys 业务键集合（订单号）
     * @return 每个有应收事实的业务键一行
     */
    @Select("""
        <script>
        SELECT kv.biz_key AS "bizKey",
               COALESCE(SUM(f.performance_amount), 0) AS "amount"
        FROM pj_perf_fact f
        JOIN (VALUES
        <foreach collection="bizKeys" item="k" separator=",">(CAST(#{k} AS text))</foreach>
        ) kv(biz_key)
          ON f.order_no = kv.biz_key
        WHERE f.fact_status = 'ACTIVE'
          AND f.fact_type = 'PERF_EXPECT'
          AND f.period = #{period}
        GROUP BY kv.biz_key
        </script>
        """)
    List<com.panjia.performance.dto.BatchFactBindRow> selectExpectSumsByBizKeys(
        @Param("period") String period, @Param("bizKeys") List<String> bizKeys);

    /**
     * 按事实 ID 查询所属合同的基本信息（调整单详情展示用）。
     *
     * @param factId 事实 ID
     * @return 合同摘要；查不到返回 null
     */
    @Select("""
        SELECT f.contract_no AS "contractNo",
               f.order_no AS "orderNo",
               f.biz_type AS "bizType",
               f.property_address AS "propertyAddress",
               f.business_date AS "businessDate"
        FROM pj_perf_fact f
        WHERE f.id = #{factId}
          AND f.contract_no IS NOT NULL
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
        SELECT COALESCE(f.contract_no, MAX(f.order_no)) AS "contractNo",
               MAX(f.order_no) AS "orderNo",
               MAX(f.biz_type) AS "bizType",
               MAX(f.property_address) AS "propertyAddress",
               MAX(f.business_date) AS "businessDate"
        FROM pj_perf_fact f
        WHERE f.fact_status = 'ACTIVE'
          AND f.period = #{period}
          AND (f.contract_no = #{contractNo} OR f.order_no = #{contractNo})
        GROUP BY f.contract_no
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
               f.role_type AS "roleType",
               f.role_name AS "roleName",
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
        WHERE f.fact_status = 'ACTIVE'
          AND f.period = #{period}
          AND f.fact_type = #{factType}
          AND (f.contract_no = #{contractNo} OR f.order_no = #{contractNo})
        ORDER BY e.employee_name, d.dept_id, f.role_type, f.id
        """)
    List<AdjustFactDetailVo> selectAdjustFactDetails(@Param("period") String period,
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
     * @param employeeId 员工 ID（可选：经纪人本人数据权限，仅聚合该员工参与的合同及其本人事实行）
     * @param offset     偏移量
     * @param pageSize   每页条数
     * @return 合同维度业绩汇总列表
     */
    @Select("""
        <script>
        WITH contract_period AS (
            SELECT CASE WHEN f.biz_type IN ('一手房','房产金融','家装荐客')
                        THEN COALESCE(f.order_no, f.contract_no)
                        ELSE COALESCE(f.contract_no, f.order_no) END AS biz_key,
                   MAX(f.period) AS max_period
            FROM pj_perf_fact f
            WHERE f.fact_status = 'ACTIVE'
              AND CASE WHEN f.biz_type IN ('一手房','房产金融','家装荐客')
                       THEN COALESCE(f.order_no, f.contract_no)
                       ELSE COALESCE(f.contract_no, f.order_no) END IS NOT NULL
            <if test="period != null and period != ''">
              AND f.period = #{period}
            </if>
            <if test="deptId != null">
              AND (f.dept_id = #{deptId}
                   OR EXISTS (SELECT 1 FROM sys_dept sd WHERE sd.dept_id = f.dept_id
                              AND sd.ancestors LIKE CONCAT('%', #{deptId}, '%')))
            </if>
            <if test="employeeId != null">
              AND f.employee_id = #{employeeId}
            </if>
            <if test="bizType != null and bizType != ''">
              AND f.biz_type = #{bizType}
            </if>
            <if test="keyword != null and keyword != ''">
              AND (f.contract_no ILIKE CONCAT('%', #{keyword}::text, '%')
                OR f.order_no ILIKE CONCAT('%', #{keyword}::text, '%')
                OR f.property_address ILIKE CONCAT('%', #{keyword}::text, '%'))
            </if>
            GROUP BY 1
        ),
        reversed_expect AS (
            SELECT DISTINCT ON (pf.source_key)
                   pf.source_key, pf.performance_amount
            FROM pj_perf_fact pf
            WHERE pf.fact_type = 'PERF_EXPECT'
              AND pf.fact_status = 'REVERSED'
            <if test="period != null and period != ''">
              AND pf.period = #{period}
            </if>
            ORDER BY pf.source_key, pf.id ASC
        ),
        fact_agg AS (
            SELECT cp.biz_key AS "bizKey",
                   cp.max_period AS "period",
                   MAX(f.contract_no) AS "contractNo",
                   MAX(f.order_no) AS "orderNo",
                   MAX(f.biz_type) AS "bizType",
                   MAX(f.property_address) AS "propertyAddress",
                   MAX(f.business_date) AS "signDate",
                   COALESCE(SUM(CASE WHEN f.fact_type = 'PERF_EXPECT' THEN f.performance_amount ELSE 0 END), 0) AS "expectAmount",
                   COALESCE(SUM(CASE WHEN f.fact_type = 'PERF_EXPECT'
                                     THEN COALESCE(re.performance_amount, f.performance_amount) ELSE 0 END), 0) AS "expectOriginalAmount",
                   COALESCE(SUM(CASE WHEN f.fact_type = 'PERF_REAL' THEN f.performance_amount ELSE 0 END), 0) AS "realAmount",
                   BOOL_OR(f.adjust_id IS NOT NULL) AS "hasAdjust",
                   COUNT(DISTINCT f.employee_id) AS "employeeCount",
                   COUNT(*) AS "detailCount"
            FROM contract_period cp
            JOIN pj_perf_fact f ON f.fact_status = 'ACTIVE'
                AND CASE WHEN f.biz_type IN ('一手房','房产金融','家装荐客')
                         THEN COALESCE(f.order_no, f.contract_no)
                         ELSE COALESCE(f.contract_no, f.order_no) END = cp.biz_key
            LEFT JOIN reversed_expect re ON re.source_key = f.source_key
            <if test="employeeId != null">
              WHERE f.employee_id = #{employeeId}
            </if>
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
               fa."employeeCount",
               fa."detailCount",
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
    List<PerformanceFactSearchVo> selectFactSearchByContract(
            @Param("period") String period,
            @Param("deptId") Long deptId,
            @Param("bizType") String bizType,
            @Param("keyword") String keyword,
            @Param("employeeId") Long employeeId,
            @Param("offset") long offset,
            @Param("pageSize") int pageSize);

    /**
     * 完整业绩查询的合同数（分页 total，按业务键去重）。
     */
    @Select("""
        <script>
        SELECT COUNT(DISTINCT CASE WHEN f.biz_type IN ('一手房','房产金融','家装荐客')
                                    THEN COALESCE(f.order_no, f.contract_no)
                                    ELSE COALESCE(f.contract_no, f.order_no) END)
        FROM pj_perf_fact f
        WHERE f.fact_status = 'ACTIVE'
          AND CASE WHEN f.biz_type IN ('一手房','房产金融','家装荐客')
                   THEN COALESCE(f.order_no, f.contract_no)
                   ELSE COALESCE(f.contract_no, f.order_no) END IS NOT NULL
          <if test="period != null and period != ''">
            AND f.period = #{period}
          </if>
          <if test="deptId != null">
            AND (f.dept_id = #{deptId}
                 OR EXISTS (SELECT 1 FROM sys_dept sd WHERE sd.dept_id = f.dept_id
                            AND sd.ancestors LIKE CONCAT('%', #{deptId}, '%')))
          </if>
          <if test="employeeId != null">
            AND f.employee_id = #{employeeId}
          </if>
          <if test="bizType != null and bizType != ''">
            AND f.biz_type = #{bizType}
          </if>
          <if test="keyword != null and keyword != ''">
            AND (
              f.contract_no ILIKE CONCAT('%', #{keyword}::text, '%')
              OR f.order_no ILIKE CONCAT('%', #{keyword}::text, '%')
              OR f.property_address ILIKE CONCAT('%', #{keyword}::text, '%')
            )
          </if>
        </script>
        """)
    long countFactSearchByContract(
            @Param("period") String period,
            @Param("deptId") Long deptId,
            @Param("bizType") String bizType,
            @Param("keyword") String keyword,
            @Param("employeeId") Long employeeId);

    /**
     * 完整业绩查询的业务类型下拉选项：在与列表完全相同的数据范围（期间/部门子树/经纪人本人）
     * 内，对 ACTIVE 事实的 biz_type 去重排序；不含关键字过滤，避免输入关键字后选项被清空。
     */
    @Select("""
        <script>
        SELECT DISTINCT f.biz_type
        FROM pj_perf_fact f
        WHERE f.fact_status = 'ACTIVE'
          AND f.biz_type IS NOT NULL
          <if test="period != null and period != ''">
            AND f.period = #{period}
          </if>
          <if test="deptId != null">
            AND (f.dept_id = #{deptId}
                 OR EXISTS (SELECT 1 FROM sys_dept sd WHERE sd.dept_id = f.dept_id
                            AND sd.ancestors LIKE CONCAT('%', #{deptId}, '%')))
          </if>
          <if test="employeeId != null">
            AND f.employee_id = #{employeeId}
          </if>
        ORDER BY 1
        </script>
        """)
    List<String> selectSearchBizTypes(
            @Param("period") String period,
            @Param("deptId") Long deptId,
            @Param("employeeId") Long employeeId);

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
               f.period AS "period",
               f.dept_id AS "deptId",
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
               f.role_type AS "roleType",
               f.role_name AS "roleName",
               f.share_ratio AS "shareRatio",
               f.business_date AS "businessDate",
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
        LEFT JOIN LATERAL (
            SELECT ci.id, ci.application_id
            FROM pj_commission_item ci
            WHERE ci.performance_fact_id = f.id
              AND ci.status <> 'REVERSED'
            ORDER BY ci.id
            LIMIT 1
        ) ci ON TRUE
        LEFT JOIN pj_commission_application ca ON ca.id = ci.application_id
                                              AND ca.status IN ('APPROVED', 'LOCKED', 'CLOSED')
        WHERE f.fact_status = 'ACTIVE'
          AND f.fact_type = 'PERF_EXPECT'
          AND (f.contract_no = #{bizNo} OR f.order_no = #{bizNo})
        ORDER BY f.period DESC, e.employee_name, d.dept_id, f.role_type, f.id
        """)
    List<PerformanceSearchDetailVo> selectSearchDetailRows(@Param("bizNo") String bizNo);

    /**
     * 按业绩事实 ID 批量查业务类型（factId → bizType）。
     * <p>
     * 仅做业绩域内自有数据的标识解析；折算比例本身由 {@code ConversionFactorPort} 统一提供，
     * 本 Mapper 不再直连 {@code pj_payroll_conversion_rule}。
     *
     * @param factIds 业绩事实 ID 集合（非空）
     * @return 每行含 factId / bizType
     */
    @Select("""
        <script>
        SELECT f.id AS "factId", f.biz_type AS "bizType"
        FROM pj_perf_fact f
        WHERE f.id IN
        <foreach collection="factIds" item="fid" open="(" separator="," close=")">#{fid}</foreach>
        </script>
    """)
    List<java.util.Map<String, Object>> selectBizTypeByFactIds(@Param("factIds") java.util.Collection<Long> factIds);

    /**
     * 按合同号 + 期间 + 事实口径查业务类型（合同级调整无 factId 时用）。
     *
     * @return bizType；查不到返回 null
     */
    @Select("""
        SELECT f2.biz_type
        FROM pj_perf_fact f2
        WHERE f2.fact_status = 'ACTIVE'
          AND f2.period = #{period}
          AND f2.fact_type = #{factType}
          AND (f2.contract_no = #{contractNo} OR f2.order_no = #{contractNo})
        LIMIT 1
    """)
    String selectBizTypeByContract(@Param("period") String period,
                                   @Param("contractNo") String contractNo,
                                   @Param("factType") String factType);

    /**
     * 批量查事实的结佣状态（settled / settleDate）。
     * <p>
     * 每个事实取最早一条非 REVERSED 的结佣明细，关联其审批单取 lock_time。
     * 与主查询分离，避免 LATERAL JOIN 逐行子查询。
     *
     * @param factIds 事实 ID 集合
     * @return 每行含 factId / settleDate；空集合返回空列表
     */
    @Select("""
        <script>
        SELECT DISTINCT ON (ci.performance_fact_id)
               ci.performance_fact_id AS "factId",
               ca.lock_time AS "settleDate"
        FROM pj_commission_item ci
        LEFT JOIN pj_commission_application ca ON ca.id = ci.application_id
                                              AND ca.status IN ('APPROVED', 'LOCKED', 'CLOSED')
        WHERE ci.status &lt;&gt; 'REVERSED'
          AND ci.performance_fact_id IN
        <foreach collection="factIds" item="id" open="(" separator="," close=")">#{id}</foreach>
        ORDER BY ci.performance_fact_id, ci.id ASC
        </script>
        """)
    List<Map<String, Object>> selectSettledInfoByFactIds(@Param("factIds") Collection<Long> factIds);
}

