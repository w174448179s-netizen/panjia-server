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
import org.apache.ibatis.annotations.Update;
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
     * 导入批次撤销：软删该批次事实（fact_status→REVERSED）。
     * <p>物理删会断结佣/调整 FK，软删保留历史痕迹。
     *
     * @param batchId 撤销的导入批次 ID
     * @param factType 事实口径（PERF_EXPECT / PERF_REAL）
     * @param reason 撤销原因（写入 reversed_reason）
     * @return 被撤销的事实条数
     */
    @Update("UPDATE pj_perf_fact SET fact_status = 'REVERSED', reversed_reason = #{reason}, update_time = NOW() " +
            "WHERE batch_id = #{batchId} AND fact_type = #{factType} AND fact_status = 'ACTIVE'")
    int markBatchRevoked(@Param("batchId") Long batchId, @Param("factType") String factType, @Param("reason") String reason);

    /**
     * 保底撤销：恢复被冲销旧批次的业绩事实（REVERSED/SUPERSEDE → ACTIVE）。
     * <p>误导入批次被撤销时，它曾通过批次 supersede 冲销的旧批次事实需要翻回生效。
     * 仅恢复 reversed_reason='SUPERSEDE' 的行——调整冲销（ADJUST）、重归一化
     * （RENORMALIZE）、撤销（BATCH_REVOKE）等其他原因的 REVERSED 行不受影响。
     *
     * @param batchIds 被恢复的旧批次 ID 列表
     * @return 恢复生效的事实条数
     */
    @Update("""
        <script>
        UPDATE pj_perf_fact
        SET fact_status = 'ACTIVE', reversed_reason = NULL, update_time = NOW()
        WHERE fact_status = 'REVERSED' AND reversed_reason = 'SUPERSEDE'
          AND batch_id IN
          <foreach collection='batchIds' item='oldBatchId' open='(' separator=',' close=')'>#{oldBatchId}</foreach>
        </script>
        """)
    int restoreSupersededFacts(@Param("batchIds") List<Long> batchIds);

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
     * - 实收建单已物理拆分至 pj_received_detail / pj_received_contract，PERF_REAL 不再落 pj_perf_fact；
     * - 期望侧批量匹配 selectExpectSumsByBizKeys：纯 `f.order_no = 键`，
     *   不再 OR contract_no（订单号与合同号 1:1，简化为单一键）。
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
               CASE WHEN BOOL_OR(f.fact_status = 'ACTIVE') THEN 'ACTIVE' ELSE 'VOIDED' END AS "factStatus",
               -- 加人调整口径：合同下存在 ACTIVE 的 MANUAL-ADJ 事实行即为「新增角色人」已生效
               -- （驳回/撤销会冲销该行为 REVERSED，故无需再关联调整单表判断）
               BOOL_OR(f.fact_status = 'ACTIVE' AND f.source = 'MANUAL' AND f.source_key LIKE '%|MANUAL-ADJ%') AS "hasAddMember"
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
        ORDER BY "businessDate" DESC, COALESCE(MAX(f.order_no), MAX(f.contract_no))
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
     * <p>
     * 匹配口径：传入键命中 {@code order_no} 或 {@code contract_no} 任一即可（二者在贝壳原始行中 1:1）。
     * 列表分组维度 {@link #selectManagePageContracts} 用 COALESCE(order_no, contract_no)，
     * 但本处传入的展示键可能是合同号也可能是订单号（取决于前端 resolveBizNo），故必须用 OR 双列匹配，
     * 不能用 COALESCE IN ——否则传入合同号而该行 order_no 非空时 COALESCE 取 order_no 导致漏命中。
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
               f.source_key AS sourceKey,
               (f.source = 'MANUAL' AND f.source_key LIKE '%|MANUAL-ADJ%') AS "manualAdjust"
        FROM pj_perf_fact f
        WHERE f.fact_status IN ('ACTIVE', 'VOIDED')
          AND f.period = #{period}
          AND f.fact_type = #{factType}
          AND (f.order_no IN
          <foreach collection="contractNos" item="cn" open="(" separator="," close=")">#{cn}</foreach>
              OR f.contract_no IN
          <foreach collection="contractNos" item="cn" open="(" separator="," close=")">#{cn}</foreach>)
        ORDER BY f.contract_no, f.employee_id, businessDate, f.role_type
        </script>
        """)
    List<PerformanceManageVo> selectManageListByContractNos(@Param("period") String period,
                                                             @Param("factType") String factType,
                                                             @Param("contractNos") List<String> contractNos);

    /**
     * 按业务键集合查询实收明细（结佣业绩口径，合同维度树表懒加载数据源）。
     * <p>
     * 拆表后 PERF_REAL 已迁出 pj_perf_fact：实收侧以 {@code pj_received_detail rd}
     * JOIN {@code pj_received_contract rc} 为底（ACTIVE 明细），员工 ID 按
     * employee_external_code 关联 pj_people_employee 回填（导入时 rd.employee_id 暂留空）。
     * <p>
     * 投影列与 {@link #selectManageListByContractNos} 完全对齐，结佣审批详情
     * （未发起模式）按「员工+角色」合并每行实收金额。
     *
     * @param period      归属期间
     * @param contractNos 业务键集合（不能为空；订单号/合同号双列 OR 匹配）
     * @return 实收明细行
     */
    @Select("""
        <script>
        SELECT rd.id,
               'ACTIVE' AS "factStatus",
               'PERF_REAL' AS "factType",
               rd.period,
               rc.business_date AS "businessDate",
               rc.order_no AS "orderNo",
               rc.contract_no AS "contractNo",
               rc.biz_type AS "bizType",
               rc.property_address AS "propertyAddress",
               rd.fee_item AS "feeItem",
               e.employee_id AS "employeeId",
               rd.role_type AS "roleType",
               rd.role_name AS "roleName",
               rd.share_ratio AS "shareRatio",
               rd.performance_amount AS "amount",
               rd.source_key AS "sourceKey",
               FALSE AS "manualAdjust"
        FROM pj_received_detail rd
        JOIN pj_received_contract rc ON rc.id = rd.contract_id
        LEFT JOIN pj_people_employee e ON e.employee_code = rd.employee_external_code
        WHERE rd.detail_status = 'ACTIVE'
          AND rd.period = #{period}
          AND (rc.order_no IN
          <foreach collection="contractNos" item="cn" open="(" separator="," close=")">#{cn}</foreach>
              OR rc.contract_no IN
          <foreach collection="contractNos" item="cn" open="(" separator="," close=")">#{cn}</foreach>)
        ORDER BY rc.contract_no, e.employee_id, rc.business_date, rd.role_type, rd.id
        </script>
        """)
    List<PerformanceManageVo> selectReceivedManageListByContractNos(
            @Param("period") String period,
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
          AND COALESCE(vf.order_no, vf.contract_no) = COALESCE(mf.order_no, mf.contract_no)
        """)
    long countVoidedSiblingsByFactId(@Param("factId") Long factId);

    /**
     * 按业务键前缀定位退单红冲对应的原正数 ACTIVE 事实（成交月原事实）。
     * <p>
     * 事实 source_key = {@code sourceType-recordSourceKey-period}，同一笔业务
     * （订单|合同|角色人|费项|角色类型）在成交月与退单月仅末尾 period 不同，
     * 故用 POSITION 做严格前缀匹配（避免 LIKE 下业务键含 _ / % 的歧义），
     * 取最早期间的一条正数事实作为红冲镜像源。
     * <p>
     * 排除当前批次：同一批次内同业务键的正负两行（如签约补录 + 比例变更调整同批出现）
     * 不是"跨月退单"，互冲会误判超额；红冲只应对历史批次的成交月原事实镜像。
     *
     * @param sourceKeyPrefix 业务键前缀（sourceType + "-" + recordSourceKey + "-"，不含 period）
     * @param factType        事实口径
     * @param excludeBatchId  当前批次 ID（其事实不参与原事实匹配）
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
          AND (f.batch_id IS NULL OR f.batch_id != #{excludeBatchId})
        ORDER BY f.period ASC, f.id ASC
        LIMIT 1
        """)
    PerformanceFact selectOriginalPositiveFact(@Param("sourceKeyPrefix") String sourceKeyPrefix,
                                               @Param("factType") String factType,
                                               @Param("excludeBatchId") Long excludeBatchId);

    /**
     * 同业务键前缀下所有 ACTIVE 正事实的业绩合计（红冲超额校验基准）。
     * <p>
     * 口径：该合同该人该业务线（订单|合同|角色|费项|角色类型）跨全部月份的正业绩总额。
     * 退单可冲减的上限 = 历史累计正业绩，而非单条原事实金额。
     */
    @Select("""
        SELECT COALESCE(SUM(f.performance_amount), 0)
        FROM pj_perf_fact f
        WHERE f.fact_status = 'ACTIVE'
          AND f.fact_type = #{factType}
          AND f.performance_amount > 0
          AND POSITION(#{sourceKeyPrefix} IN f.source_key) = 1
          AND LENGTH(f.source_key) = LENGTH(#{sourceKeyPrefix}) + 7
        """)
    java.math.BigDecimal sumPositiveBySourceKeyPrefix(@Param("sourceKeyPrefix") String sourceKeyPrefix,
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
               f.share_ratio AS "shareRatio"
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
     * 拆表后 PERF_REAL 已迁出 pj_perf_fact：实收侧以 pj_received_detail 为底
     * （JOIN pj_received_contract 取合同号/订单号），员工信息按 employee_external_code
     * 关联 pj_people_employee（导入时 rd.employee_id 暂留空）。
     * <p>
     * 应收金额按「同合同 + 同员工工号 + 同角色」配对 ACTIVE PERF_EXPECT 求和
     * （同员工同角色可能跨多个费项，故聚合为一行）；金额口径<b>当月优先</b>——当月有
     * <b>非零</b>新签 → 只取当月合计；当月为 0/无 → 不参与当月计算，取历史（&lt;实收月）合计，
     * 与建单/结佣口径一致。
     * 应收原值沿 ACTIVE 行 sourceKey 链取最早 REVERSED 金额聚合，与「合同业绩明细」页 originalAmount 同口径。
     * 实收侧（rd）暂无调整链，originalAmount = amount、receivedAdjusted 恒为 false。
     *
     * @param period     归属期间
     * @param contractNo 合同号/订单号（双列匹配）
     * @return 每人实收明细行
     */
    @Select("""
        <script>
        WITH rc AS (
            SELECT rc.id, rc.order_no, rc.contract_no
            FROM pj_received_contract rc
            WHERE rc.period = #{period}
              AND (rc.contract_no = #{contractNo} OR rc.order_no = #{contractNo})
        ),
        expect_scope AS (
            -- 新签金额口径：当月有非零新签 → 只取当月；
            -- 当月为 0/无 → 不参与当月计算，取历史（实收月之前），与建单/结佣口径一致
            SELECT EXISTS (
                SELECT 1 FROM pj_perf_fact pc
                WHERE pc.fact_status = 'ACTIVE' AND pc.fact_type = 'PERF_EXPECT'
                  AND pc.period = #{period}
                  AND pc.performance_amount != 0
                  AND (pc.contract_no = #{contractNo} OR pc.order_no = #{contractNo})
            ) AS has_current
        ),
        active_expect AS (
            SELECT pe.employee_external_code AS emp_code, pe.role_type,
                   SUM(pe.performance_amount) AS exp_amt
            FROM pj_perf_fact pe, expect_scope es
            WHERE pe.fact_status = 'ACTIVE'
              AND pe.fact_type = 'PERF_EXPECT'
              AND (pe.contract_no = #{contractNo} OR pe.order_no = #{contractNo})
              AND ((es.has_current AND pe.period = #{period})
                   OR (NOT es.has_current AND pe.period &lt; #{period}))
            GROUP BY pe.employee_external_code, pe.role_type
        ),
        reversed_chain AS (
            SELECT DISTINCT ON (a.source_key)
                   a.employee_external_code AS emp_code, a.role_type,
                   a.order_no AS order_no, a.contract_no AS contract_no,
                   a.source_key, b.performance_amount AS orig_amt
            FROM pj_perf_fact a
            JOIN pj_perf_fact b ON b.source_key = a.source_key
               AND b.fact_type = 'PERF_EXPECT' AND b.fact_status = 'REVERSED'
            CROSS JOIN expect_scope es
            WHERE a.fact_status = 'ACTIVE'
              AND a.fact_type = 'PERF_EXPECT'
              AND (a.contract_no = #{contractNo} OR a.order_no = #{contractNo})
              AND ((es.has_current AND a.period = #{period})
                   OR (NOT es.has_current AND a.period &lt; #{period}))
            ORDER BY a.source_key, b.id ASC
        ),
        original_expect AS (
            SELECT emp_code, role_type, order_no, contract_no, SUM(orig_amt) AS orig_amt
            FROM reversed_chain
            GROUP BY 1, 2, 3, 4
        )
        SELECT rd.id AS "factId",
               e.employee_id AS "employeeId",
               COALESCE(e.employee_code, rd.employee_external_code) AS "employeeCode",
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
               rd.role_type AS "roleType",
               rd.role_name AS "roleName",
               rd.share_ratio AS "shareRatio",
               ae.exp_amt AS "expectedAmount",
               COALESCE(oe.orig_amt, ae.exp_amt) AS "originalExpectedAmount",
               (oe.orig_amt IS NOT NULL) AS "expectedAdjusted",
               rd.performance_amount AS "amount",
               rd.performance_amount AS "originalAmount",
               FALSE AS "receivedAdjusted"
        FROM rc
        JOIN pj_received_detail rd ON rd.contract_id = rc.id AND rd.detail_status = 'ACTIVE'
        LEFT JOIN pj_people_employee e ON e.employee_code = rd.employee_external_code
        LEFT JOIN sys_dept d ON d.dept_id = e.dept_id
        LEFT JOIN sys_dept p ON p.dept_id = d.parent_id
        LEFT JOIN sys_dept gp ON gp.dept_id = p.parent_id
        LEFT JOIN active_expect ae ON ae.emp_code IS NOT DISTINCT FROM rd.employee_external_code
                                  AND ae.role_type IS NOT DISTINCT FROM rd.role_type
        LEFT JOIN original_expect oe ON (oe.order_no = rc.order_no OR oe.contract_no = rc.contract_no)
                                    AND oe.emp_code IS NOT DISTINCT FROM rd.employee_external_code
                                    AND oe.role_type IS NOT DISTINCT FROM rd.role_type
        ORDER BY e.employee_name, e.dept_id, rd.role_type, rd.id
        </script>
        """)
    List<ReceivedFactDetailVo> selectReceivedFactDetails(@Param("period") String period,
                                                           @Param("contractNo") String contractNo);

    /**
     * 按期间 + 业务键集合查询实收明细列表的补充字段（涉及人数、应收合计），每传入键一行。
     * <p>
     * 拆表后 PERF_REAL 已迁出 pj_perf_fact：实收合计/涉及人数取实收域
     * {@code pj_received_contract rc + pj_received_detail rd}（ACTIVE 明细），
     * 员工去重按员工主数据 ID（rd.employee_id 暂留空时回退外部工号）。
     * <p>
     * 应收合计仍取 ACTIVE PERF_EXPECT（含已生效调整），使列表「新签业绩」显示调整后金额；
     * 金额口径当月优先：当月有<b>非零</b>新签 → 只取当月合计；当月为 0/无 → 不参与当月计算，
     * 取历史（&lt;实收月）合计，与建单/结佣口径一致。
     * 实收明细（rd）暂无调整链，originalReceivedAmount 与 receivedAmount 同值
     * （前端据此不展示「原值 → 调整后值」）。
     * 匹配口径：传入键命中 {@code contract_no} 或 {@code order_no} 任一即可（二者 1:1，
     * 兼容早期把订单号写进 contract_no 的一手房单据）。
     *
     * @param period      归属期间
     * @param contractNos 单据上的合同号/订单号集合（不可为空，调用方需先过滤）
     * @return 每键一行的业务类型、涉及人数、实收/应收合计
     */
    @Select("""
        <script>
        SELECT k.key AS "contractNo",
               MAX(rc.biz_type) AS "bizType",
               COUNT(DISTINCT COALESCE(e.employee_id::text, rd.employee_external_code)) AS "employeeCount",
               COALESCE(SUM(rd.performance_amount), 0) AS "receivedAmount",
               COALESCE(SUM(rd.performance_amount), 0) AS "originalReceivedAmount",
               COALESCE((
                   SELECT SUM(pe.performance_amount)
                   FROM pj_perf_fact pe
                   WHERE pe.fact_status = 'ACTIVE' AND pe.fact_type = 'PERF_EXPECT'
                     AND (pe.contract_no = k.key OR pe.order_no = k.key)
                     AND (
                           (pe.period = #{period} AND EXISTS (
                               SELECT 1 FROM pj_perf_fact pc
                               WHERE pc.fact_status = 'ACTIVE' AND pc.fact_type = 'PERF_EXPECT'
                                 AND pc.period = #{period}
                                 AND pc.performance_amount != 0
                                 AND (pc.contract_no = k.key OR pc.order_no = k.key)))
                        OR (pe.period &lt; #{period} AND NOT EXISTS (
                               SELECT 1 FROM pj_perf_fact pc
                               WHERE pc.fact_status = 'ACTIVE' AND pc.fact_type = 'PERF_EXPECT'
                                 AND pc.period = #{period}
                                 AND pc.performance_amount != 0
                                 AND (pc.contract_no = k.key OR pc.order_no = k.key)))
                         )
               ), 0) AS "expectedAmount"
        FROM (VALUES
          <foreach collection="contractNos" item="cn" separator=",">(#{cn})</foreach>
        ) AS k(key)
        JOIN pj_received_contract rc ON rc.period = #{period}
                                    AND (rc.contract_no = k.key OR rc.order_no = k.key)
        JOIN pj_received_detail rd ON rd.contract_id = rc.id AND rd.detail_status = 'ACTIVE'
        LEFT JOIN pj_people_employee e ON e.employee_code = rd.employee_external_code
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
     * <p>
     * 增加角色人（ADD_MEMBER）例外：新角色人事实是凭空新增的链（sourceKey 含
     * MANUAL-ADJ/MANUAL-CADJ），调整前该角色在合同上不存在，其链按 0 计入原额；
     * 否则新人金额会被同时计入「原值」与「现值」（钱只是从一个人转到另一个人，
     * 合同总额不变），虚增一个新人金额。
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
            SELECT DISTINCT ON (sk.source_key) sk.source_key,
                   CASE
                       WHEN sk.source_key LIKE '%MANUAL-ADJ%'
                         OR sk.source_key LIKE '%MANUAL-CADJ%' THEN 0
                       ELSE x.performance_amount
                   END AS amt
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
                     AND (COALESCE(e.order_no, e.contract_no)) = s.biz_key
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
            SELECT COALESCE(f.order_no, f.contract_no) AS biz_key,
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
              AND COALESCE(f.order_no, f.contract_no) IS NOT NULL
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
     * 跨合同去重员工数：指定期间/口径下，业务键（合同号或订单号）命中集合的 ACTIVE 事实
     * 中 COUNT(DISTINCT employee_id)（结佣明细列表合计口径，与 selectManageSummary 一致）。
     */
    @Select("""
        <script>
        SELECT COUNT(DISTINCT f.employee_id)
        FROM pj_perf_fact f
        WHERE f.fact_status = 'ACTIVE'
          AND f.period = #{period}
          AND f.fact_type = #{factType}
          AND f.employee_id IS NOT NULL
          AND (
            f.contract_no IN
              <foreach collection="keys" item="bk" open="(" separator="," close=")">#{bk}</foreach>
            OR f.order_no IN
              <foreach collection="keys" item="bk" open="(" separator="," close=")">#{bk}</foreach>
          )
        </script>
        """)
    long selectDistinctEmployeeCountByKeys(@Param("period") String period,
                                           @Param("factType") String factType,
                                           @Param("keys") Collection<String> keys);

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
        SELECT COALESCE(MAX(f.contract_no),MAX(f.order_no)) AS "contractNo",
               MAX(f.order_no) AS "orderNo",
               MAX(f.biz_type) AS "bizType",
               MAX(f.property_address) AS "propertyAddress",
               MAX(f.business_date) AS "businessDate"
        FROM pj_perf_fact f
        WHERE f.fact_status = 'ACTIVE'
          AND f.period = #{period}
          AND (f.contract_no = #{contractNo} OR f.order_no = #{contractNo})
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
               (CASE WHEN #{factType} = 'PERF_EXPECT' THEN
                   -- 拆表后实收落在 rd：按「同期间 + 合同双键 + 同工号 + 同角色」配对，
                   -- rd 每组(合同,工号,角色)仅 1 行、应收侧可能多行，按组内应收行数均摊实收
                   (SELECT ROUND(grp.real_sum / NULLIF(grp.exp_cnt, 0), 2) FROM (
                       SELECT COALESCE(SUM(rdx.performance_amount), 0) AS real_sum,
                              (SELECT COUNT(*) FROM pj_perf_fact fc
                                WHERE fc.fact_status = 'ACTIVE'
                                  AND fc.fact_type = 'PERF_EXPECT'
                                  AND fc.period = f.period
                                  AND (fc.order_no = f.order_no OR fc.contract_no = f.contract_no
                                       OR fc.order_no = f.contract_no OR fc.contract_no = f.order_no)
                                  AND fc.employee_external_code IS NOT DISTINCT FROM f.employee_external_code
                                  AND fc.role_type IS NOT DISTINCT FROM f.role_type) AS exp_cnt
                       FROM pj_received_detail rdx
                       JOIN pj_received_contract rc ON rc.id = rdx.contract_id
                       WHERE rdx.detail_status = 'ACTIVE'
                         AND rdx.period = f.period
                         AND (rc.order_no = f.order_no OR rc.contract_no = f.contract_no
                              OR rc.order_no = f.contract_no OR rc.contract_no = f.order_no)
                         AND rdx.employee_external_code IS NOT DISTINCT FROM f.employee_external_code
                         AND rdx.role_type IS NOT DISTINCT FROM f.role_type
                   ) grp)
                   ELSE
                   -- 实收口径行（当前无此路径，保留应收 sourceKey 配对）
                   (SELECT pe.performance_amount
                      FROM pj_perf_fact pe
                     WHERE pe.fact_status = 'ACTIVE'
                       AND pe.fact_type = 'PERF_EXPECT'
                       AND pe.source_key = f.source_key
                     ORDER BY pe.id
                     LIMIT 1)
                END) AS "expectedAmount",
               f.performance_amount AS "amount",
               f.source_key AS "sourceKey",
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
        WITH all_rows AS (
            -- 应收侧（ACTIVE PERF_EXPECT）
            SELECT COALESCE(f.order_no, f.contract_no) AS biz_key,
                   f.period AS period,
                   f.dept_id AS dept_id,
                   f.employee_id AS employee_id,
                   f.biz_type AS biz_type,
                   f.order_no AS order_no,
                   f.contract_no AS contract_no,
                   f.property_address AS property_address,
                   f.business_date AS business_date,
                   f.adjust_id AS adjust_id,
                   f.source_key AS source_key,
                   f.employee_external_code AS emp_code,
                   f.role_type AS role_type,
                   f.performance_amount AS amount,
                   'E' AS src
            FROM pj_perf_fact f
            WHERE f.fact_status = 'ACTIVE'
              AND f.fact_type = 'PERF_EXPECT'
              AND COALESCE(f.order_no, f.contract_no) IS NOT NULL
            UNION ALL
            -- 实收侧（拆表后 ACTIVE rd + rc；rd.employee_id 暂留空，按工号关联员工主数据）
            SELECT COALESCE(rc.order_no, rc.contract_no),
                   rd.period,
                   COALESCE(rd.dept_id, rc.dept_id),
                   e.employee_id,
                   rc.biz_type,
                   rc.order_no,
                   rc.contract_no,
                   rc.property_address,
                   rc.business_date,
                   rd.adjust_id,
                   rd.source_key,
                   rd.employee_external_code,
                   rd.role_type,
                   rd.performance_amount,
                   'R'
            FROM pj_received_detail rd
            JOIN pj_received_contract rc ON rc.id = rd.contract_id
            LEFT JOIN pj_people_employee e ON e.employee_code = rd.employee_external_code
            WHERE rd.detail_status = 'ACTIVE'
              AND COALESCE(rc.order_no, rc.contract_no) IS NOT NULL
        ),
        kf AS (
            SELECT * FROM all_rows
            WHERE 1 = 1
            <if test="period != null and period != ''">
              AND period = #{period}
            </if>
            <if test="deptId != null">
              AND (dept_id = #{deptId}
                   OR EXISTS (SELECT 1 FROM sys_dept sd WHERE sd.dept_id = dept_id
                              AND sd.ancestors LIKE CONCAT('%', #{deptId}, '%')))
            </if>
            <if test="employeeId != null">
              AND employee_id = #{employeeId}
            </if>
            <if test="bizType != null and bizType != ''">
              AND biz_type = #{bizType}
            </if>
            <if test="keyword != null and keyword != ''">
              AND (contract_no ILIKE CONCAT('%', #{keyword}::text, '%')
                OR order_no ILIKE CONCAT('%', #{keyword}::text, '%')
                OR property_address ILIKE CONCAT('%', #{keyword}::text, '%'))
            </if>
        ),
        contract_period AS (
            SELECT biz_key, MAX(period) AS max_period
            FROM kf
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
                   MAX(ar.contract_no) AS "contractNo",
                   MAX(ar.order_no) AS "orderNo",
                   MAX(ar.biz_type) AS "bizType",
                   MAX(ar.property_address) AS "propertyAddress",
                   MAX(ar.business_date) AS "signDate",
                   COALESCE(SUM(CASE WHEN ar.src = 'E' THEN ar.amount ELSE 0 END), 0) AS "expectAmount",
                   COALESCE(SUM(CASE WHEN ar.src = 'E'
                                     THEN COALESCE(re.performance_amount, ar.amount) ELSE 0 END), 0) AS "expectOriginalAmount",
                   COALESCE(SUM(CASE WHEN ar.src = 'R' THEN ar.amount ELSE 0 END), 0) AS "realAmount",
                   BOOL_OR(ar.adjust_id IS NOT NULL) AS "hasAdjust",
                   COUNT(DISTINCT COALESCE(ar.employee_id::text, ar.emp_code)) AS "employeeCount",
                   COUNT(*) AS "detailCount"
            FROM contract_period cp
            JOIN all_rows ar ON ar.biz_key = cp.biz_key
            LEFT JOIN reversed_expect re ON re.source_key = ar.source_key
            <if test="employeeId != null">
              WHERE ar.employee_id = #{employeeId}
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
               lc.total_amount AS "commissionAmount",
               EXISTS (
                   SELECT 1 FROM pj_perf_adjust pa2
                   WHERE pa2.period = fa."period"
                     AND pa2.adjust_type = 'ADD_MEMBER'
                     AND pa2.status IN ('SUBMITTED', 'APPROVING', 'EXECUTED')
                     AND pa2.contract_no IN (fa."contractNo", fa."orderNo")
               ) AS "hasAddMember"
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
     * <p>
     * 业务键口径与列表一致：ACTIVE PERF_EXPECT（pj_perf_fact）与 ACTIVE 实收
     * （rd + rc）两侧 UNION 后去重，筛选条件（期间/部门子树/员工/类型/关键字）
     * 与 {@link #selectFactSearchByContract} 的 kf 完全相同。
     */
    @Select("""
        <script>
        WITH all_rows AS (
            SELECT COALESCE(f.order_no, f.contract_no) AS biz_key,
                   f.period AS period,
                   f.dept_id AS dept_id,
                   f.employee_id AS employee_id,
                   f.biz_type AS biz_type,
                   f.contract_no AS contract_no,
                   f.order_no AS order_no,
                   f.property_address AS property_address
            FROM pj_perf_fact f
            WHERE f.fact_status = 'ACTIVE'
              AND f.fact_type = 'PERF_EXPECT'
              AND COALESCE(f.order_no, f.contract_no) IS NOT NULL
            UNION ALL
            SELECT COALESCE(rc.order_no, rc.contract_no),
                   rd.period,
                   COALESCE(rd.dept_id, rc.dept_id),
                   e.employee_id,
                   rc.biz_type,
                   rc.contract_no,
                   rc.order_no,
                   rc.property_address
            FROM pj_received_detail rd
            JOIN pj_received_contract rc ON rc.id = rd.contract_id
            LEFT JOIN pj_people_employee e ON e.employee_code = rd.employee_external_code
            WHERE rd.detail_status = 'ACTIVE'
              AND COALESCE(rc.order_no, rc.contract_no) IS NOT NULL
        )
        SELECT COUNT(DISTINCT biz_key)
        FROM all_rows
        WHERE 1 = 1
          <if test="period != null and period != ''">
            AND period = #{period}
          </if>
          <if test="deptId != null">
            AND (dept_id = #{deptId}
                 OR EXISTS (SELECT 1 FROM sys_dept sd WHERE sd.dept_id = dept_id
                            AND sd.ancestors LIKE CONCAT('%', #{deptId}, '%')))
          </if>
          <if test="employeeId != null">
            AND employee_id = #{employeeId}
          </if>
          <if test="bizType != null and bizType != ''">
            AND biz_type = #{bizType}
          </if>
          <if test="keyword != null and keyword != ''">
            AND (
              contract_no ILIKE CONCAT('%', #{keyword}::text, '%')
              OR order_no ILIKE CONCAT('%', #{keyword}::text, '%')
              OR property_address ILIKE CONCAT('%', #{keyword}::text, '%')
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
     * 内，对 ACTIVE 应收（PERF_EXPECT）与实收（rd+rc）两侧的 biz_type 去重排序；
     * 不含关键字过滤，避免输入关键字后选项被清空。
     */
    @Select("""
        <script>
        WITH all_rows AS (
            SELECT f.period AS period,
                   f.dept_id AS dept_id,
                   f.employee_id AS employee_id,
                   f.biz_type AS biz_type
            FROM pj_perf_fact f
            WHERE f.fact_status = 'ACTIVE'
              AND f.fact_type = 'PERF_EXPECT'
              AND f.biz_type IS NOT NULL
            UNION ALL
            SELECT rd.period,
                   COALESCE(rd.dept_id, rc.dept_id),
                   e.employee_id,
                   rc.biz_type
            FROM pj_received_detail rd
            JOIN pj_received_contract rc ON rc.id = rd.contract_id
            LEFT JOIN pj_people_employee e ON e.employee_code = rd.employee_external_code
            WHERE rd.detail_status = 'ACTIVE'
              AND rc.biz_type IS NOT NULL
        )
        SELECT DISTINCT biz_type
        FROM all_rows
        WHERE 1 = 1
          <if test="period != null and period != ''">
            AND period = #{period}
          </if>
          <if test="deptId != null">
            AND (dept_id = #{deptId}
                 OR EXISTS (SELECT 1 FROM sys_dept sd WHERE sd.dept_id = dept_id
                            AND sd.ancestors LIKE CONCAT('%', #{deptId}, '%')))
          </if>
          <if test="employeeId != null">
            AND employee_id = #{employeeId}
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
     * 以 PERF_EXPECT（新签/应收）ACTIVE 事实为基准行；拆表后实收落在 rd，
     * 按「同期间 + 合同双键 + 同工号 + 同角色」配对实收金额（rd 每组一行），
     * 一行同时展示应收/实收双口径；含该业务键全部期间
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
               CASE
                 -- 增加角色人（ADD_MEMBER）执行后插入的新人事实：来源 MANUAL 且 source_key 带 MANUAL-调整单号标记，
                 -- 无 REVERSED 前序事实，调整前业绩按 0 展示（0 → X）
                 WHEN f.source = 'MANUAL' AND f.source_key LIKE '%|MANUAL-ADJ%' THEN 0
                 ELSE COALESCE(
                   (SELECT pf.performance_amount FROM pj_perf_fact pf
                    WHERE pf.source_key = f.source_key
                      AND pf.fact_type = 'PERF_EXPECT'
                      AND pf.fact_status = 'REVERSED'
                    ORDER BY pf.id ASC LIMIT 1),
                   f.performance_amount)
               END AS "originalExpectAmount",
               COALESCE(
                 -- 拆表后实收落在 rd：按「同期间 + 合同双键 + 同工号 + 同角色」配对
                 (SELECT SUM(rdx.performance_amount)
                  FROM pj_received_detail rdx
                  JOIN pj_received_contract rc ON rc.id = rdx.contract_id
                  WHERE rdx.detail_status = 'ACTIVE'
                    AND rdx.period = f.period
                    AND (rc.order_no = f.order_no OR rc.contract_no = f.contract_no
                         OR rc.order_no = f.contract_no OR rc.contract_no = f.order_no)
                    AND rdx.employee_external_code IS NOT DISTINCT FROM f.employee_external_code
                    AND rdx.role_type IS NOT DISTINCT FROM f.role_type),
                 0
               ) AS "realAmount",
               (ci.id IS NOT NULL) AS "settled",
               ca.lock_time AS "settleDate",
               (f.source = 'MANUAL' AND f.source_key LIKE '%|MANUAL-ADJ%') AS "manualAdjust"
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

