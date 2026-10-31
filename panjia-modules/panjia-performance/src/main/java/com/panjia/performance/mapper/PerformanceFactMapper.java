package com.panjia.performance.mapper;

import com.panjia.contracts.dto.PerformanceContractSummaryDTO;
import com.panjia.contracts.dto.PerformanceFactSummaryDTO;
import com.panjia.performance.domain.PerformanceFact;
import com.panjia.performance.domain.vo.AdjustFactDetailVo;
import com.panjia.performance.domain.vo.PerformanceFactSearchVo;
import com.panjia.performance.domain.vo.PerformanceManageContractVo;
import com.panjia.performance.domain.vo.PerformanceManageVo;
import com.panjia.performance.domain.vo.PerformanceRankVo;
import com.panjia.performance.domain.vo.PerformanceSearchDetailVo;
import com.panjia.performance.domain.vo.PerformanceSummaryVo;
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
     * 全局汇总（与过滤条件一致，跨所有页）：明细数、合同数、涉及人数、金额合计，
     * 同时按业务类型分组返回各组金额（供服务层按类型系数累加出「折算后合计」）。
     * <p>
     * 一次聚合出两种粒度（GROUPING SETS）：{@code isTotal=1} 的行为全局汇总行
     * （{@code bizType} 为空但由 {@code GROUPING()} 区分，避免与 biz_type 为空的真实分组混淆），
     * 其余行为各业务类型分组行。相比「全局汇总 + 按类型汇总」两条同条件 SQL，只扫一遍事实表
     * （2026-10-29 优化）。
     */
    @Select("""
        <script>
        SELECT GROUPING(f.biz_type) AS "isTotal",
               f.biz_type AS "bizType",
               COUNT(*) AS "detailCount",
               COUNT(DISTINCT CASE WHEN f.biz_type IN ('一手房','房产金融','家装荐客') THEN COALESCE(NULLIF(f.order_no,''), NULLIF(f.contract_no,'')) ELSE COALESCE(NULLIF(f.contract_no,''), NULLIF(f.order_no,'')) END) AS "contractCount",
               COUNT(DISTINCT f.employee_id) AS "employeeCount",
               COALESCE(SUM(f.performance_amount), 0) AS "totalAmount"
        FROM pj_perf_fact f
        WHERE
        <choose>
          <when test="factStatus == 'ALL'">f.fact_status IN ('ACTIVE', 'VOIDED')</when>
          <!-- 已退单：合同存在已执行退单（退单表即合同退单状态源），业绩事实本身仍为 ACTIVE -->
          <when test="factStatus == 'REFUNDED'">f.fact_status = 'ACTIVE'</when>
          <when test="factStatus != null and factStatus != ''">f.fact_status = #{factStatus}</when>
          <otherwise>f.fact_status = 'ACTIVE'</otherwise>
        </choose>
        <if test="factStatus == 'REFUNDED'">
          AND EXISTS (
            SELECT 1 FROM pj_perf_refund r
            WHERE r.status = 'EXECUTED'
              AND (r.contract_no = f.contract_no
                   OR (r.order_no IS NOT NULL AND r.order_no = f.order_no))
          )
        </if>
        <!-- 退单三态筛选（行级红冲行口径）：REFUND=仅红冲行，NORMAL=排除红冲行，空=全部 -->
        <if test="refundFilter == 'REFUND'">
          AND f.reversal_type = 'REDINK_REFUND'
        </if>
        <if test="refundFilter == 'NORMAL'">
          AND (f.reversal_type IS NULL OR f.reversal_type != 'REDINK_REFUND')
        </if>
          <if test="period != null and period != ''">AND f.period = #{period}</if>
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
        GROUP BY GROUPING SETS ((f.biz_type), ())
        </script>
        """)
    java.util.List<java.util.Map<String, Object>> selectManageSummaryWithGroups(@Param("period") String period,
                                                      @Param("factType") String factType,
                                                      @Param("deptId") Long deptId,
                                                      @Param("employeeId") Long employeeId,
                                                      @Param("bizType") String bizType,
                                                      @Param("keyword") String keyword,
                                                      @Param("factStatus") String factStatus,
                                                      @Param("refundFilter") String refundFilter,
                                                      @Param("selfEmployeeId") Long selfEmployeeId);


    /**
     * 查询指定期间/口径下出现过的业务类型（去重排序），供筛选下拉使用。
     * period 为空时查全部期间（跨期合同号搜索场景）。
     */
    @Select("""
        <script>
        SELECT DISTINCT biz_type
        FROM pj_perf_fact
        WHERE fact_status = 'ACTIVE'
          <if test="period != null and period != ''">AND period = #{period}</if>
          AND fact_type = #{factType}
          AND biz_type IS NOT NULL
        ORDER BY 1
        </script>
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
     * 业务键（订单号 / 合同号）统一口径（2026-10-29 定稿）：
     *
     *   CASE WHEN biz_type IN ('一手房','房产金融','家装荐客')
     *        THEN COALESCE(NULLIF(order_no,''),    NULLIF(contract_no,''))
     *        ELSE COALESCE(NULLIF(contract_no,''), NULLIF(order_no,''))
     *   END
     *
     * 即一手房/房产金融/家装荐客以订单号为准（空回退合同号），其余业务以合同号为准（空回退订单号）；
     * 与前端 src/utils/panjiaBiz.ts 的 resolveBizNo 完全一致（前端按此口径决定展示/传参用哪个号）。
     *
     * 为什么不能用 COALESCE(order_no, contract_no)（订单号优先）：
     * 贝壳新签源与实收源的 order_no 不一定一致（实测单 100015671805：新签 order_no=合同号，
     * 实收 order_no=14567723），订单号优先会让同一笔业务在新签侧/实收侧算出两个业务键，
     * 业绩查询（两侧 UNION）因此把同一单拆成两行。
     *
     * 使用约束：
     * - 【聚合/分组/去重维度】必须用上面的 CASE 表达式（本文件已全量统一）；
     * - 【匹配传入键】不要用该表达式做等值比较，仍须 `键 = contract_no OR 键 = order_no` 双列兜底
     *   （调用方回传的可能是合同号也可能是订单号，且历史单据 contract_no 里可能存的是订单号）。
     */

    /**
     * 业绩管理合同维度分页（以统一业务键为分页维度，口径见上方注释）。
     * <p>
     * 每业务键一行：合同号/订单号/业务类型/房源地址/签约日期/合同金额合计/涉及人数/明细数/未结算数。
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
               -- 期间：单期间筛选=该期间；跨期合并=合同涉及全部期间按序拼接（与实收列表合并行口径一致）
               STRING_AGG(DISTINCT f.period, '、' ORDER BY f.period) AS "period",
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
          <!-- 已退单：合同存在已执行退单（退单表即合同退单状态源），业绩事实本身仍为 ACTIVE -->
          <when test="factStatus == 'REFUNDED'">f.fact_status = 'ACTIVE'</when>
          <when test="factStatus != null and factStatus != ''">f.fact_status = #{factStatus}</when>
          <otherwise>f.fact_status = 'ACTIVE'</otherwise>
        </choose>
        <if test="factStatus == 'REFUNDED'">
          AND EXISTS (
            SELECT 1 FROM pj_perf_refund r
            WHERE r.status = 'EXECUTED'
              AND (r.contract_no = f.contract_no
                   OR (r.order_no IS NOT NULL AND r.order_no = f.order_no))
          )
        </if>
          <if test="period != null and period != ''">AND f.period = #{period}</if>
          AND f.fact_type = #{factType}
          AND COALESCE(f.order_no, f.contract_no) IS NOT NULL
        <!-- 退单三态筛选（行级红冲行口径）：REFUND=仅红冲行，NORMAL=排除红冲行，空=全部 -->
        <if test="refundFilter == 'REFUND'">
          AND f.reversal_type = 'REDINK_REFUND'
        </if>
        <if test="refundFilter == 'NORMAL'">
          AND (f.reversal_type IS NULL OR f.reversal_type != 'REDINK_REFUND')
        </if>
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
        GROUP BY CASE WHEN f.biz_type IN ('一手房','房产金融','家装荐客') THEN COALESCE(NULLIF(f.order_no,''), NULLIF(f.contract_no,'')) ELSE COALESCE(NULLIF(f.contract_no,''), NULLIF(f.order_no,'')) END
        ORDER BY "businessDate" DESC,
                 MAX(CASE WHEN f.biz_type IN ('一手房','房产金融','家装荐客') THEN COALESCE(NULLIF(f.order_no,''), NULLIF(f.contract_no,'')) ELSE COALESCE(NULLIF(f.contract_no,''), NULLIF(f.order_no,'')) END)
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
                                            @Param("refundFilter") String refundFilter,
                                            @Param("selfEmployeeId") Long selfEmployeeId,
                                            @Param("offset") long offset,
                                            @Param("pageSize") int pageSize);

    /**
     * 统计符合条件的合同数（分页 total，按统一业务键去重，口径同 {@link #selectManagePageContracts}）。
     * 参数语义同 {@link #selectManagePageContracts}。
     */
    @Select("""
        <script>
        SELECT COUNT(DISTINCT CASE WHEN f.biz_type IN ('一手房','房产金融','家装荐客') THEN COALESCE(NULLIF(f.order_no,''), NULLIF(f.contract_no,'')) ELSE COALESCE(NULLIF(f.contract_no,''), NULLIF(f.order_no,'')) END)
        FROM pj_perf_fact f
        WHERE
        <choose>
          <when test="factStatus == 'ALL'">f.fact_status IN ('ACTIVE', 'VOIDED')</when>
          <!-- 已退单：合同存在已执行退单（退单表即合同退单状态源），业绩事实本身仍为 ACTIVE -->
          <when test="factStatus == 'REFUNDED'">f.fact_status = 'ACTIVE'</when>
          <when test="factStatus != null and factStatus != ''">f.fact_status = #{factStatus}</when>
          <otherwise>f.fact_status = 'ACTIVE'</otherwise>
        </choose>
        <if test="factStatus == 'REFUNDED'">
          AND EXISTS (
            SELECT 1 FROM pj_perf_refund r
            WHERE r.status = 'EXECUTED'
              AND (r.contract_no = f.contract_no
                   OR (r.order_no IS NOT NULL AND r.order_no = f.order_no))
          )
        </if>
        <!-- 退单三态筛选（行级红冲行口径）：REFUND=仅红冲行，NORMAL=排除红冲行，空=全部 -->
        <if test="refundFilter == 'REFUND'">
          AND f.reversal_type = 'REDINK_REFUND'
        </if>
        <if test="refundFilter == 'NORMAL'">
          AND (f.reversal_type IS NULL OR f.reversal_type != 'REDINK_REFUND')
        </if>
          <if test="period != null and period != ''">AND f.period = #{period}</if>
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
                              @Param("refundFilter") String refundFilter,
                              @Param("selfEmployeeId") Long selfEmployeeId);

    /**
     * 按业务键集合查询业绩明细（合同维度树表懒加载数据源）。
     * <p>
     * 单表查询 pj_perf_fact，employeeName/employeeCode/deptPath/originalAmount/settled/settleDate
     * 由 Service 层批量补充查询填充，避免 CTE + 5 个 JOIN 的复杂执行计划。
     * <p>
     * 匹配口径：传入键命中 {@code order_no} 或 {@code contract_no} 任一即可（二者在贝壳原始行中 1:1）。
     * 列表分组维度 {@link #selectManagePageContracts} 用统一业务键，但本处传入的展示键可能是
     * 合同号也可能是订单号（取决于前端 resolveBizNo），故必须用 OR 双列匹配，
     * 不能用业务键表达式 IN ——否则传入合同号而该行按业务键取 order_no 时导致漏命中。
     *
     * @param contractNos 业务键集合（不能为空；列表行展示的合同号/订单号）
     * @param orderNos    订单号集合（可空；非空时按 order_no 精确限定单订单，
     *                    与调整链路「订单号优先匹配」口径一致，避免同合同号多订单明细混排）
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
          <if test="period != null and period != ''">AND f.period = #{period}</if>
          AND f.fact_type = #{factType}
          <choose>
            <when test="orderNos != null and orderNos.size() > 0">
              AND f.order_no IN
              <foreach collection="orderNos" item="on" open="(" separator="," close=")">#{on}</foreach>
            </when>
            <otherwise>
              AND (f.order_no IN
              <foreach collection="contractNos" item="cn" open="(" separator="," close=")">#{cn}</foreach>
                  OR f.contract_no IN
              <foreach collection="contractNos" item="cn" open="(" separator="," close=")">#{cn}</foreach>)
            </otherwise>
          </choose>
        ORDER BY f.contract_no, f.employee_id, businessDate, f.role_type
        </script>
        """)
    List<PerformanceManageVo> selectManageListByContractNos(@Param("period") String period,
                                                             @Param("factType") String factType,
                                                             @Param("contractNos") List<String> contractNos,
                                                             @Param("orderNos") List<String> orderNos);

    /**
     * 按业务键集合批量查询合同维度业务类型（调整单列表「调整对象」号源展示回填用）。
     * <p>
     * 只取 DISTINCT 合同维度行（期间/口径/合同号/订单号/业务类型），不查明细金额，轻量回填。
     * 键命中 {@code contract_no} 或 {@code order_no} 任一即可（调整单 contract_no 存的是提交时
     * 展示键，可能本身就是订单号），服务层再按期间+口径+订单号优先口径精确配对。
     *
     * @param keys 业务键集合（调整单 contract_no + order_no 合集，不能为空）
     * @return 每行含 factType/period/contractNo/orderNo/bizType；空集合返回空列表
     */
    @Select("""
        <script>
        SELECT DISTINCT f.fact_type AS factType,
                        f.period AS period,
                        f.contract_no AS contractNo,
                        f.order_no AS orderNo,
                        f.biz_type AS bizType
        FROM pj_perf_fact f
        WHERE f.fact_status IN ('ACTIVE', 'VOIDED')
          AND (f.contract_no IN
          <foreach collection="keys" item="k" open="(" separator="," close=")">#{k}</foreach>
            OR f.order_no IN
          <foreach collection="keys" item="k" open="(" separator="," close=")">#{k}</foreach>)
        </script>
        """)
    List<Map<String, Object>> selectBizTypesByKeys(@Param("keys") Collection<String> keys);

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
               -- 调整前原值：同 source_key 链最早一条 REVERSED 明细金额；实收加人调整新插行（MANUAL-RADJ 标记）无前序链按 0；
               -- 无链（未调整）回退当前值，前端按 originalAmount ≠ amount 判定「已调整」
               CASE WHEN rd.source_key LIKE '%|MANUAL-RADJ-%' THEN 0
                    ELSE COALESCE((SELECT rdr.performance_amount FROM pj_received_detail rdr
                                   WHERE rdr.source_key = rd.source_key AND rdr.detail_status = 'REVERSED'
                                   ORDER BY rdr.id ASC LIMIT 1), rd.performance_amount) END AS "originalAmount",
               rd.source_key AS "sourceKey",
               FALSE AS "manualAdjust"
        FROM pj_received_detail rd
        JOIN pj_received_contract rc ON rc.id = rd.contract_id
        LEFT JOIN pj_people_employee e ON e.employee_code = rd.employee_external_code
        WHERE rd.detail_status = 'ACTIVE'
          <if test="period != null and period != ''">AND rd.period = #{period}</if>
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
        <script>
        SELECT f.*
        FROM pj_perf_fact f
        WHERE f.fact_status = 'ACTIVE'
          <if test="period != null and period != ''">AND f.period = #{period}</if>
          AND f.fact_type = #{factType}
          AND (f.contract_no = #{contractNo} OR f.order_no = #{contractNo})
        ORDER BY f.id
        </script>
        """)
    List<PerformanceFact> selectActiveFactsByContractNo(@Param("period") String period,
                                                         @Param("factType") String factType,
                                                         @Param("contractNo") String contractNo);

    /**
     * 按订单号+合同号双键精确查询该订单下全部 ACTIVE 业绩事实。
     * <p>
     * 同一合同号可能挂多个订单号（跨月续签/改单等），按合同号装载会把多订单
     * 明细混在一起，导致指定 factId 不在集合内而报「指定调整行不在该合同业绩明细中」；
     * 订单号理论上也可能重复（不同合同挂同订单号），故双键同时满足才精确唯一。
     * contract_no 条件兼容业务键（订单号优先的历史前端传参）：传订单号时走 order_no 等值分支。
     *
     * @param period     归属期间
     * @param factType   事实口径
     * @param orderNo    订单号
     * @param contractNo 合同号（或历史业务键；为空时仅按订单号匹配）
     * @return 该订单下全部 ACTIVE 事实列表
     */
    @Select("""
        <script>
        SELECT f.*
        FROM pj_perf_fact f
        WHERE f.fact_status = 'ACTIVE'
          <if test="period != null and period != ''">AND f.period = #{period}</if>
          AND f.fact_type = #{factType}
          AND f.order_no = #{orderNo}
          <if test="contractNo != null and contractNo != ''">
            AND (f.contract_no = #{contractNo} OR f.order_no = #{contractNo})
          </if>
        ORDER BY f.id
        </script>
        """)
    List<PerformanceFact> selectActiveFactsByOrderAndContract(@Param("period") String period,
                                                              @Param("factType") String factType,
                                                              @Param("orderNo") String orderNo,
                                                              @Param("contractNo") String contractNo);

    /**
     * 统计某合同在指定口径下的<b>去重非空订单号数量</b>（双键未命中是否可安全退化按合同号匹配的闸门）。
     * <p>退化查询若放行会捞出该合同全部匹配行：仅当这些行订单维度唯一（count=1，
     * 含贝壳新签 order_no 误填成合同号的脏数据场景）时，按合同号匹配才不会跨订单串单；
     * count≥2（同合同多订单）禁止退化，count=0（无事实）无意义亦不退化。
     *
     * @param period     归属期间（可空：空时跨全部期间统计，口径须与调用方退化查询一致）
     * @param factType   事实口径
     * @param statuses   事实状态集合（可空：空时不限状态）
     * @param contractNo 合同号
     * @return 去重非空订单号数量
     */
    @Select("""
        <script>
        SELECT COUNT(DISTINCT f.order_no)
        FROM pj_perf_fact f
        WHERE f.fact_type = #{factType}
          AND f.contract_no = #{contractNo}
          AND f.order_no IS NOT NULL AND f.order_no &lt;&gt; ''
          <if test="period != null and period != ''">AND f.period = #{period}</if>
          <if test="statuses != null and statuses.size() > 0">
            AND f.fact_status IN
            <foreach collection="statuses" item="s" open="(" separator="," close=")">#{s}</foreach>
          </if>
        </script>
        """)
    long countDistinctOrderNos(@Param("period") String period,
                               @Param("factType") String factType,
                               @Param("statuses") java.util.Collection<String> statuses,
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
        <script>
        SELECT f.*
        FROM pj_perf_fact f
        WHERE f.fact_status = 'VOIDED'
          <if test="period != null and period != ''">AND f.period = #{period}</if>
          AND f.fact_type = #{factType}
          AND (f.contract_no = #{contractNo} OR f.order_no = #{contractNo})
        ORDER BY f.id
        </script>
        """)
    List<PerformanceFact> selectVoidedFactsByContractNo(@Param("period") String period,
                                                         @Param("factType") String factType,
                                                         @Param("contractNo") String contractNo);

    /**
     * 按订单号+合同号双键精确查询已作废（VOIDED）业绩事实（合同级恢复用）。
     * <p>
     * 同合同号挂多订单时，按合同号（双键 OR）会把多订单的作废明细混在一起：
     * 恢复/作废校验会跨订单误判。双键同时满足才精确唯一；
     * contract_no 条件兼容业务键（传订单号时走 order_no 等值分支）。
     *
     * @param period     归属期间
     * @param factType   事实口径
     * @param orderNo    订单号
     * @param contractNo 合同号（或历史业务键；为空时仅按订单号匹配）
     * @return 该订单下全部 VOIDED 事实列表
     */
    @Select("""
        <script>
        SELECT f.*
        FROM pj_perf_fact f
        WHERE f.fact_status = 'VOIDED'
          <if test="period != null and period != ''">AND f.period = #{period}</if>
          AND f.fact_type = #{factType}
          AND f.order_no = #{orderNo}
          <if test="contractNo != null and contractNo != ''">
            AND (f.contract_no = #{contractNo} OR f.order_no = #{contractNo})
          </if>
        ORDER BY f.id
        </script>
        """)
    List<PerformanceFact> selectVoidedFactsByOrderAndContract(@Param("period") String period,
                                                               @Param("factType") String factType,
                                                               @Param("orderNo") String orderNo,
                                                               @Param("contractNo") String contractNo);

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
               f.role_name AS "roleName",
               f.source AS "source",
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
               f.fee_item AS "feeItem"
        FROM pj_perf_fact f
        LEFT JOIN pj_perf_received_apply ra ON ra.id = f.received_apply_id
        WHERE f.id IN
        <foreach collection="factIds" item="id" open="(" separator="," close=")">#{id}</foreach>
        ORDER BY f.id
        </script>
        """)
    List<PerformanceFactSummaryDTO> selectFactSummariesByIds(@Param("factIds") Collection<Long> factIds);

    /**
     * 按业务键汇总「未被结佣明细占用」的 ACTIVE PERF_EXPECT 金额（结佣发起页可结佣净额）。
     * <p>
     * 与旧口径（全量事实行拉回应用层 + 按事实 ID 分块查 pj_commission_item）等价：
     * 存在 status &lt;&gt; 'REVERSED' 的结佣明细即视为已结佣，该事实不计入。改为 SQL 侧按
     * (order_no, contract_no) 聚合，只回传键对合计行；已结佣判定走部分唯一索引
     * uk_citem_fact_active(performance_fact_id) WHERE status &lt;&gt; 'REVERSED'，避免全量事实搬运。
     */
    @Select("""
        <script>
        SELECT pe.order_no AS "orderNo",
               pe.contract_no AS "contractNo",
               SUM(pe.performance_amount) AS "amount"
        FROM pj_perf_fact pe
        WHERE pe.fact_status = 'ACTIVE'
          AND pe.fact_type = 'PERF_EXPECT'
          <if test="employeeId != null">AND pe.employee_id = #{employeeId}</if>
          AND (pe.order_no IN
              <foreach collection="bizKeys" item="k" open="(" separator="," close=")">#{k}</foreach>
               OR pe.contract_no IN
              <foreach collection="bizKeys2" item="k" open="(" separator="," close=")">#{k}</foreach>)
          AND NOT EXISTS (
              SELECT 1 FROM pj_commission_item ci
              WHERE ci.performance_fact_id = pe.id
                AND ci.status &lt;&gt; 'REVERSED'
          )
        GROUP BY pe.order_no, pe.contract_no
        </script>
        """)
    List<PerformanceFactSummaryDTO> selectUnsettledExpectSumsByKeys(@Param("bizKeys") Collection<String> bizKeys,
                                                                    @Param("bizKeys2") Collection<String> bizKeys2,
                                                                    @Param("employeeId") Long employeeId);

    /**
     * 按业务键 + 新签归属期汇总「未被结佣明细占用」的 ACTIVE PERF_EXPECT（结佣发起页逐期成行）。
     * <p>
     * 与 {@link #selectUnsettledExpectSumsByKeys} 同口径，多带 period 维度与条数/人数，
     * 便于发起页按「新签归属月」分开展示（7 月已结佣、8 月退单待结佣时只出 8 月一行）。
     */
    @Select("""
        <script>
        SELECT pe.order_no AS "orderNo",
               pe.contract_no AS "contractNo",
               pe.period AS "period",
               SUM(pe.performance_amount) AS "expectedAmount",
               COUNT(*) AS "detailCount",
               COUNT(DISTINCT pe.employee_id) AS "employeeCount"
        FROM pj_perf_fact pe
        WHERE pe.fact_status = 'ACTIVE'
          AND pe.fact_type = 'PERF_EXPECT'
          <if test="employeeId != null">AND pe.employee_id = #{employeeId}</if>
          AND (pe.order_no IN
              <foreach collection="bizKeys" item="k" open="(" separator="," close=")">#{k}</foreach>
               OR pe.contract_no IN
              <foreach collection="bizKeys2" item="k" open="(" separator="," close=")">#{k}</foreach>)
          AND NOT EXISTS (
              SELECT 1 FROM pj_commission_item ci
              WHERE ci.performance_fact_id = pe.id
                AND ci.status &lt;&gt; 'REVERSED'
          )
        GROUP BY pe.order_no, pe.contract_no, pe.period
        </script>
        """)
    List<PerformanceContractSummaryDTO> selectUnsettledExpectGroupsByKeys(@Param("bizKeys") Collection<String> bizKeys,
                                                                          @Param("bizKeys2") Collection<String> bizKeys2,
                                                                          @Param("employeeId") Long employeeId);

    /**
     * 按期间 + 合同号查询 ACTIVE 事实摘要（含合同号/订单号/房源地址，结佣按合同发起用）。
     *
     * @param period     归属期间
     * @param factType   事实口径
     * @param contractNo 合同号
     * @return 事实摘要列表（含 amount = 0 的行，过滤由结佣域处理）
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
               f.role_name AS "roleName",
               f.source AS "source",
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
          <if test="period != null and period != ''">AND f.period = #{period}</if>
          AND f.fact_type = #{factType}
          AND (f.contract_no = #{contractNo} OR f.order_no = #{contractNo})
        ORDER BY f.id
        </script>
        """)
    List<PerformanceFactSummaryDTO> selectActiveFactSummariesByContractNo(@Param("period") String period,
                                                                          @Param("factType") String factType,
                                                                          @Param("contractNo") String contractNo);

    /**
     * 按订单号+合同号双键精确查询 ACTIVE 事实摘要（调整执行匹配用）。
     * <p>
     * 同一合同号挂多个订单号时，按合同号（双键 OR）装载会跨订单混排明细；
     * 订单号也可能重复（不同合同挂同订单号），故双键同时满足才精确唯一。
     * contract_no 条件兼容业务键（传订单号时走 order_no 等值分支）。
     *
     * @param period     归属期间（可空=不限期间）
     * @param factType   事实口径
     * @param orderNo    订单号
     * @param contractNo 合同号（或历史业务键；为空时仅按订单号匹配）
     * @return 事实摘要列表
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
               f.role_name AS "roleName",
               f.source AS "source",
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
          <if test="period != null and period != ''">AND f.period = #{period}</if>
          AND f.fact_type = #{factType}
          AND f.order_no = #{orderNo}
          <if test="contractNo != null and contractNo != ''">
            AND (f.contract_no = #{contractNo} OR f.order_no = #{contractNo})
          </if>
        ORDER BY f.id
        </script>
        """)
    List<PerformanceFactSummaryDTO> selectActiveFactSummariesByOrderAndContract(@Param("period") String period,
                                                                                @Param("factType") String factType,
                                                                                @Param("orderNo") String orderNo,
                                                                                @Param("contractNo") String contractNo);

    /**
     * 按期间 + 订单号/合同号查询实收审批单详情明细（每人一行，含应收/实收双口径）。
     * <p>
     * 拆表后 PERF_REAL 已迁出 pj_perf_fact：实收侧以 pj_received_detail 为底
     * （JOIN pj_received_contract 取合同号/订单号），员工信息按 employee_external_code
     * 关联 pj_people_employee（导入时 rd.employee_id 暂留空）。
     * <p>
     * 应收金额按「同合同 + 同员工工号 + 同角色」配对 ACTIVE PERF_EXPECT 求和
     * （同员工同角色可能跨多个费项，故聚合为一行）；金额口径=实收月及之前全部期间合计
     * （当月+历史合并，与建单 loadExpectItemFacts 2026-09-30 定稿一致）——
     * 冲正/红冲负数镜像落在实收月，旧「当月优先排斥历史」会把应收算成负数（2026-10-11 修复）。
     * 应收原值沿 ACTIVE 行 sourceKey 链取最早 REVERSED 金额聚合，与「合同业绩明细」页 originalAmount 同口径。
     * 实收原值同样沿 rd 的 sourceKey 链取最早 REVERSED 金额（实收调整 supersede 产生链），
     * receivedAdjusted 标记链上存在 REVERSED 或为「增加角色人」新人行（MANUAL-RADJ，原值按 0）。
     * orderNo 非空时按订单号限定（合同号非空再加合同号=双键精确，同合同号挂多订单防跨订单配对）；
     * orderNo 为空（历史单据）退化合同号双列 OR 旧口径；合同号与订单号都为空时不匹配任何行；
     * degradeToContract=true（调用方双侧闸门判定两侧订单都唯一）时新签侧退化仅按合同号配对，
     * 兼容贝壳新签 order_no 误填成合同号的脏数据（实收侧 JOIN 仍用双键，实收订单号本身是准确的）。
     *
     * @param period              归属期间
     * @param orderNo             订单号（可空）
     * @param contractNo          合同号（可空；仅 orderNo 为空的历史单据走双列 OR 兼容）
     * @param degradeToContract   双键未命中且双侧订单唯一时，新签侧退化仅按合同号配对
     * @param applyId             单据粒度过滤（可空）：单张单详情传入审批单 ID，只展示本单明细
     *                            （退单负数明细归属 TD 冲减单，不混入同期正常单详情）；
     *                            跨期合同详情传 null 合并全部单据明细
     * @return 每人实收明细行
     */
    @Select("""
        <script>
        WITH rc AS (
            SELECT rc.id, rc.order_no, rc.contract_no, rc.period
            FROM pj_received_contract rc
            WHERE 1=1
              <choose>
                <when test="orderNo != null and orderNo != ''">
                  AND rc.order_no = #{orderNo}
                  <if test="contractNo != null and contractNo != ''">AND rc.contract_no = #{contractNo}</if>
                </when>
                <when test="contractNo != null and contractNo != ''">AND (rc.contract_no = #{contractNo} OR rc.order_no = #{contractNo})</when>
                <otherwise>AND 1 = 0</otherwise>
              </choose>
              <if test="period != null and period != ''">AND rc.period = #{period}</if>
        ),
        reversed_chain AS (
            SELECT DISTINCT ON (a.source_key)
                   a.employee_external_code AS emp_code, a.role_type,
                   a.order_no AS order_no, a.contract_no AS contract_no,
                   a.source_key, a.fee_item AS fee_item,
                   b.performance_amount AS orig_amt
            FROM pj_perf_fact a
            JOIN pj_perf_fact b ON b.source_key = a.source_key
               AND b.fact_type = 'PERF_EXPECT' AND b.fact_status = 'REVERSED'
            WHERE a.fact_status = 'ACTIVE'
              AND a.fact_type = 'PERF_EXPECT'
              <choose>
                <when test="degradeToContract">AND a.contract_no = #{contractNo}</when>
                <when test="orderNo != null and orderNo != ''">
                  AND a.order_no = #{orderNo}
                  <if test="contractNo != null and contractNo != ''">AND a.contract_no = #{contractNo}</if>
                </when>
                <when test="contractNo != null and contractNo != ''">
                  AND (a.contract_no = #{contractNo} OR a.order_no = #{contractNo})
                </when>
                <otherwise>AND 1 = 0</otherwise>
              </choose>
              <if test="period != null and period != ''">AND a.period &lt;= #{period}</if>
            ORDER BY a.source_key, b.id ASC
        ),
        original_expect AS (
            SELECT emp_code, role_type, order_no, contract_no,
                   -- 不用 ELSE 0：该（工号+角色）组无此费用项的冲正链时必须为 NULL，
                   -- 外层 COALESCE 才会回退到当前值、expectedAdjusted 才为 false；
                   -- 若给 0，未调整的费用项会显示「0 → 当前值」的幻影调整箭头
                   SUM(CASE WHEN fee_item = '贷款服务费' THEN orig_amt END) AS loan_orig_amt,
                   SUM(CASE WHEN fee_item IS DISTINCT FROM '贷款服务费' THEN orig_amt END) AS other_orig_amt
            FROM reversed_chain
            GROUP BY 1, 2, 3, 4
        )
        SELECT rd.id AS "factId",
               rc.period AS "period",
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
               -- 应收口径：正数行取该实收期间及之前累计（含历史）；负数行（退单红冲冲减行）取当期负部新签。
               -- 行级判定而非期间级：退单月可能与正常实收单月相同（如退到 9 月而 9 月有正数实收），
               -- 期间级判定（当期合计为负）会把同月正数行误切当期口径显示成负数
               CASE WHEN rd.fee_item = '贷款服务费'
                    THEN CASE WHEN rd.performance_amount &lt; 0 THEN COALESCE(ap.loan_neg_amt, ae.loan_fee_amt) ELSE ae.loan_fee_amt END
                    ELSE CASE WHEN rd.performance_amount &lt; 0 THEN COALESCE(ap.other_neg_amt, ae.other_fee_amt) ELSE ae.other_fee_amt END
               END AS "expectedAmount",
               -- 归属审批单号：跨期详情按 TD 前缀区分退单冲减行与正常行（红冲行按月独立成行，不与正常行互抵）
               rra.apply_no AS "applyNo",
               -- 原值同口径：有调整前事实取链原值，否则回退当前值（退单行跟随当期负数，避免出现「正 → 负」假箭头）
               COALESCE(
                   CASE WHEN rd.fee_item = '贷款服务费' THEN oe.loan_orig_amt ELSE oe.other_orig_amt END,
                   CASE WHEN rd.fee_item = '贷款服务费'
                        THEN CASE WHEN rd.performance_amount &lt; 0 THEN COALESCE(ap.loan_neg_amt, ae.loan_fee_amt) ELSE ae.loan_fee_amt END
                        ELSE CASE WHEN rd.performance_amount &lt; 0 THEN COALESCE(ap.other_neg_amt, ae.other_fee_amt) ELSE ae.other_fee_amt END
                   END
               ) AS "originalExpectedAmount",
               (CASE WHEN rd.fee_item = '贷款服务费' THEN oe.loan_orig_amt ELSE oe.other_orig_amt END IS NOT NULL) AS "expectedAdjusted",
               rd.performance_amount AS "amount",
               -- 实收原值沿 rd 的 sourceKey 链取最早 REVERSED 金额（与新签 originalExpectedAmount 同口径）；
               -- 实收调整「增加角色人」新人行（MANUAL-RADJ 虚拟锚点）无前序，调整前按 0 展示（0 → X）
               CASE
                   WHEN rd.source_key LIKE '%|MANUAL-RADJ-%' THEN 0
                   ELSE COALESCE((
                       SELECT rdr.performance_amount
                       FROM pj_received_detail rdr
                       WHERE rdr.source_key = rd.source_key
                         AND rdr.detail_status = 'REVERSED'
                       ORDER BY rdr.id ASC LIMIT 1), rd.performance_amount)
               END AS "originalAmount",
               (rd.source_key LIKE '%|MANUAL-RADJ-%' OR EXISTS (
                   SELECT 1 FROM pj_received_detail rdr
                   WHERE rdr.source_key = rd.source_key
                     AND rdr.detail_status = 'REVERSED')) AS "receivedAdjusted"
        FROM rc
        JOIN pj_received_detail rd ON rd.contract_id = rc.id AND rd.detail_status = 'ACTIVE'
        -- 单据粒度：单张单详情只展示本单明细。退单负数明细挂在同期合同下但以 received_apply_id
        -- 归属 TD 冲减单，不过滤会混入正常单详情；历史未绑单明细（NULL）保留展示
        <if test="applyId != null">AND (rd.received_apply_id = #{applyId} OR rd.received_apply_id IS NULL)</if>
        LEFT JOIN pj_people_employee e ON e.employee_code = rd.employee_external_code
        LEFT JOIN pj_perf_received_apply rra ON rra.id = rd.received_apply_id
        LEFT JOIN sys_dept d ON d.dept_id = e.dept_id
        LEFT JOIN sys_dept p ON p.dept_id = d.parent_id
        LEFT JOIN sys_dept gp ON gp.dept_id = p.parent_id
        -- 应收累计（该实收期间及之前）：按行员工+角色配对，跨期明细每行各自取到本行期间
        LEFT JOIN LATERAL (
            SELECT SUM(CASE WHEN pe.fee_item = '贷款服务费' THEN pe.performance_amount ELSE 0 END) AS loan_fee_amt,
                   SUM(CASE WHEN pe.fee_item IS DISTINCT FROM '贷款服务费' THEN pe.performance_amount ELSE 0 END) AS other_fee_amt
            FROM pj_perf_fact pe
            WHERE pe.fact_status = 'ACTIVE'
              AND pe.fact_type = 'PERF_EXPECT'
              AND pe.period &lt;= rc.period
              AND pe.employee_external_code IS NOT DISTINCT FROM rd.employee_external_code
              AND pe.role_type IS NOT DISTINCT FROM rd.role_type
              <choose>
                <when test="degradeToContract">AND pe.contract_no = #{contractNo}</when>
                <when test="orderNo != null and orderNo != ''">
                  AND pe.order_no = #{orderNo}
                  <if test="contractNo != null and contractNo != ''">AND pe.contract_no = #{contractNo}</if>
                </when>
                <when test="contractNo != null and contractNo != ''">
                  AND (pe.contract_no = #{contractNo} OR pe.order_no = #{contractNo})
                </when>
                <otherwise>AND 1 = 0</otherwise>
              </choose>
        ) ae ON TRUE
        -- 应收当期（仅本行期间）：loan_fee_amt/other_fee_amt 为当期净额，
        -- loan_neg_amt/other_neg_amt 为当期负部（仅负数事实，退单红冲行展示用，无负部时回退累计口径）
        LEFT JOIN LATERAL (
            SELECT SUM(CASE WHEN pe.fee_item = '贷款服务费' THEN pe.performance_amount ELSE 0 END) AS loan_fee_amt,
                   SUM(CASE WHEN pe.fee_item IS DISTINCT FROM '贷款服务费' THEN pe.performance_amount ELSE 0 END) AS other_fee_amt,
                   SUM(CASE WHEN pe.fee_item = '贷款服务费' AND pe.performance_amount &lt; 0 THEN pe.performance_amount END) AS loan_neg_amt,
                   SUM(CASE WHEN pe.fee_item IS DISTINCT FROM '贷款服务费' AND pe.performance_amount &lt; 0 THEN pe.performance_amount END) AS other_neg_amt
            FROM pj_perf_fact pe
            WHERE pe.fact_status = 'ACTIVE'
              AND pe.fact_type = 'PERF_EXPECT'
              AND pe.period = rc.period
              AND pe.employee_external_code IS NOT DISTINCT FROM rd.employee_external_code
              AND pe.role_type IS NOT DISTINCT FROM rd.role_type
              <choose>
                <when test="degradeToContract">AND pe.contract_no = #{contractNo}</when>
                <when test="orderNo != null and orderNo != ''">
                  AND pe.order_no = #{orderNo}
                  <if test="contractNo != null and contractNo != ''">AND pe.contract_no = #{contractNo}</if>
                </when>
                <when test="contractNo != null and contractNo != ''">
                  AND (pe.contract_no = #{contractNo} OR pe.order_no = #{contractNo})
                </when>
                <otherwise>AND 1 = 0</otherwise>
              </choose>
        ) ap ON TRUE
        LEFT JOIN original_expect oe ON
                                    <choose>
                                      <when test="degradeToContract">
                                        oe.contract_no = rc.contract_no
                                      </when>
                                      <when test="orderNo != null and orderNo != ''">
                                        <if test="contractNo != null and contractNo != ''">
                                          oe.contract_no = rc.contract_no
                                          AND
                                        </if>
                                        oe.order_no = rc.order_no
                                      </when>
                                      <otherwise>
                                        (oe.order_no = rc.order_no OR oe.contract_no = rc.contract_no)
                                      </otherwise>
                                    </choose>
                                    AND oe.emp_code IS NOT DISTINCT FROM rd.employee_external_code
                                    AND oe.role_type IS NOT DISTINCT FROM rd.role_type
        -- 期间优先：跨期详情按 8、9、10 月逐段排列（单期详情所有行同期间，顺序不变）
        ORDER BY rc.period, e.employee_name, e.dept_id, rd.role_type, rd.id
        </script>
        """)
    List<ReceivedFactDetailVo> selectReceivedFactDetails(@Param("period") String period,
                                                           @Param("orderNo") String orderNo,
                                                           @Param("contractNo") String contractNo,
                                                           @Param("degradeToContract") boolean degradeToContract,
                                                           @Param("applyId") Long applyId);

    /**
     * 按期间 + (合同号,订单号) 键对集合查询实收明细列表的补充字段（涉及人数、应收合计），每键对一行。
     * <p>
     * 拆表后 PERF_REAL 已迁出 pj_perf_fact：实收合计/涉及人数取实收域
     * {@code pj_received_contract rc + pj_received_detail rd}（ACTIVE 明细），
     * 员工去重按员工主数据 ID（rd.employee_id 暂留空时回退外部工号）。
     * <p>
     * 应收合计仍取 ACTIVE PERF_EXPECT（含已生效调整），使列表「新签业绩」显示调整后金额；
     * 金额口径为<b>累计</b>：实收月及之前全部期间的新签合计（跨月冲正/调整新增一并计入），
     * 与明细弹窗、结佣列表口径一致。曾用「当月有非零只取当月」的当月优先口径，
     * 在当月发生冲正/补录时会把历史新签整体丢掉（合同新签从 4.5 万骤降为当月金额），已修正。
     * 实收明细（rd）暂无调整链，originalReceivedAmount 与 receivedAmount 同值
     * （前端据此不展示「原值 → 调整后值」）。
     * 匹配口径（订单号为唯一业务锚点，四分支）：
     * degrade=true（调用方双侧闸门：新签事实/实收合同两侧该合同去重订单号都恰 1）→
     * 应收子查询退化仅按合同号配对，兼容贝壳新签 order_no 误填成合同号的脏数据
     * （实收侧 JOIN 仍用双键，实收订单号本身是准确的）；
     * 合同号+订单号齐全（非退化）→ 双键同时满足（同合同号挂多订单防串单）；
     * 合同号为空串（一手房/家装荐客等无合同号业务）→ 按订单号；
     * 订单号为空串（历史单据）→ 退化 contract_no/order_no 双键 OR 旧口径。
     *
     * @param period 归属期间；为空时返回合同粒度净额行（每键对一行：新签=全部期间 ACTIVE 净额，
     *               退单负数与历史正数相抵，全额退单后归零，供跨期合并行/合同详情头部使用）；
     *               非空时返回单据粒度分部口径行（每键对每张审批单一行：正常单正部累计、TD 冲减单当期负部）
     * @param keys   键对集合（每项含 contractNo 可空串、orderNo 可空串，两者不全空、degrade=true/false；不可为空）
     * @return 每键对一行的业务类型、涉及人数、实收/应收合计
     */
    @Select("""
        <script>
        SELECT k.ck AS "contractNo",
               k.ok AS "orderNo",
               <if test="period != null and period != ''">
               -- 单据粒度（仅单期口径）：退单负实收明细挂在同期已有合同行（contract_id）下、以 received_apply_id
               -- 区分 TD 冲减单；按合同聚合会把负数算进正常单，且正常单与 TD 单各算一遍导致合并翻倍。
               -- 跨期合并行（period 为空）按合同粒度聚合，不输出 applyId
               rd.received_apply_id AS "applyId",
               </if>
               MAX(rc.biz_type) AS "bizType",
               COUNT(DISTINCT COALESCE(e.employee_id::text, rd.employee_external_code)) AS "employeeCount",
               COALESCE(SUM(rd.performance_amount), 0) AS "receivedAmount",
               COALESCE(SUM(rd.performance_amount), 0) AS "originalReceivedAmount",
               <choose>
                   <when test="period == null or period == ''">
                   -- 跨期合并行/合同详情头部（period 为空）：全部期间 ACTIVE 新签净额，
                   -- 退单负数与历史正数相抵，全额退单后归零（与结佣侧合并行净额口径一致）
                   COALESCE((
                       SELECT SUM(pe.performance_amount)
                       FROM pj_perf_fact pe
                       WHERE pe.fact_status = 'ACTIVE' AND pe.fact_type = 'PERF_EXPECT'
                         AND (
                                   (k.dg AND pe.contract_no = k.ck)
                                OR (NOT k.dg AND k.ok &lt;&gt; '' AND k.ck &lt;&gt; '' AND pe.contract_no = k.ck AND pe.order_no = k.ok)
                                OR (NOT k.dg AND k.ck = '' AND pe.order_no = k.ok)
                                OR (NOT k.dg AND k.ok = '' AND (pe.contract_no = k.ck OR pe.order_no = k.ck))
                             )
                   ), 0)
                   </when>
                   <otherwise>
                   CASE
                       -- 退单负实收行（当期合计为负）用「当期负部新签」展示本次冲减对应的新签退单
                       WHEN COALESCE(SUM(rd.performance_amount), 0) &lt; 0 THEN COALESCE((
                           SELECT SUM(CASE WHEN pe.performance_amount &lt; 0 THEN pe.performance_amount ELSE 0 END)
                           FROM pj_perf_fact pe
                           WHERE pe.fact_status = 'ACTIVE' AND pe.fact_type = 'PERF_EXPECT'
                             AND (
                                       (k.dg AND pe.contract_no = k.ck)
                                    OR (NOT k.dg AND k.ok &lt;&gt; '' AND k.ck &lt;&gt; '' AND pe.contract_no = k.ck AND pe.order_no = k.ok)
                                    OR (NOT k.dg AND k.ck = '' AND pe.order_no = k.ok)
                                    OR (NOT k.dg AND k.ok = '' AND (pe.contract_no = k.ck OR pe.order_no = k.ck))
                                 )
                             AND pe.period = #{period}
                       ), 0)
                       -- 正常行：正部累计（仅正数事实），退单负事实不抵消正常单的新签显示；
                       -- 新签调整走 supersede+新增（旧事实离开 ACTIVE），不受正部口径影响
                       ELSE COALESCE((
                           SELECT SUM(CASE WHEN pe.performance_amount &gt; 0 THEN pe.performance_amount ELSE 0 END)
                           FROM pj_perf_fact pe
                           WHERE pe.fact_status = 'ACTIVE' AND pe.fact_type = 'PERF_EXPECT'
                             AND (
                                       (k.dg AND pe.contract_no = k.ck)
                                    OR (NOT k.dg AND k.ok &lt;&gt; '' AND k.ck &lt;&gt; '' AND pe.contract_no = k.ck AND pe.order_no = k.ok)
                                    OR (NOT k.dg AND k.ck = '' AND pe.order_no = k.ok)
                                    OR (NOT k.dg AND k.ok = '' AND (pe.contract_no = k.ck OR pe.order_no = k.ck))
                                 )
                             -- 累计口径：实收月及之前全部期间的新签合计（含跨月冲正/调整新增）；
                             -- 不可用「当月有非零只取当月」，否则本月冲正后会把历史新签整体丢掉
                             AND pe.period &lt;= #{period}
                       ), 0)
                   END
                   </otherwise>
               </choose> AS "expectedAmount"
        FROM (VALUES
          <foreach collection="keys" item="k" separator=",">(CAST(#{k.contractNo} AS text), CAST(#{k.orderNo} AS text), CAST(#{k.degrade} AS boolean))</foreach>
        ) AS k(ck, ok, dg)
        JOIN pj_received_contract rc ON (
              (k.ok &lt;&gt; '' AND k.ck &lt;&gt; '' AND rc.contract_no = k.ck AND rc.order_no = k.ok)
           OR (k.ck = '' AND rc.order_no = k.ok)
           OR (k.ok = '' AND (rc.contract_no = k.ck OR rc.order_no = k.ck))
        )
        JOIN pj_received_detail rd ON rd.contract_id = rc.id AND rd.detail_status = 'ACTIVE'
        LEFT JOIN pj_people_employee e ON e.employee_code = rd.employee_external_code
        WHERE 1=1
        <if test="period != null and period != ''">AND rc.period = #{period}</if>
        <choose>
            <when test="period == null or period == ''">GROUP BY k.ck, k.ok, k.dg</when>
            <otherwise>GROUP BY k.ck, k.ok, k.dg, rd.received_apply_id</otherwise>
        </choose>
        </script>
        """)
    List<ReceivedContractMetricsVo> selectReceivedContractMetrics(
        @Param("period") String period,
        @Param("keys") java.util.Collection<java.util.Map<String, String>> keys);

    /**
     * 批量查合同维度「调整前」事实金额合计（结佣合同列表展示「原值 → 调整后值」用）。
     * <p>
     * 以各业务键当前 ACTIVE 事实的 sourceKey 集合为准，沿事实链（同 sourceKey，
     * 不区分 fact_status）取 id 最早一条事实金额求和——未调整时最早一条即 ACTIVE 自身，
     * 已调整（新签/实收调整 supersede 均保留 sourceKey）时为最早 REVERSED 原值。
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
                               <if test="period != null and period != ''">AND f.period = #{period}</if>
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
                               <if test="period != null and period != ''">AND x.period = #{period}</if>
            ORDER BY sk.source_key, x.id ASC
        ),
        rev AS (
            SELECT DISTINCT sk2.source_key
            FROM sk sk2
            JOIN pj_perf_fact y ON y.source_key = sk2.source_key
                               AND y.fact_type = #{factType}
                               AND y.fact_status = 'REVERSED'
        )
        SELECT sk.key AS "bizKey",
               COALESCE(SUM(orig.amt), 0) AS "originalAmount",
               -- 链上存在 REVERSED（supersede 冲销）或为调整新增角色人链（MANUAL-ADJ/MANUAL-CADJ）即「已调整」；
               -- AMOUNT 类调整总额不变时原值=现值，仅靠金额比较会漏标，故以链存在性为准
               BOOL_OR(sk.source_key LIKE '%MANUAL-ADJ%'
                       OR sk.source_key LIKE '%MANUAL-CADJ%'
                       OR sk.source_key IN (SELECT source_key FROM rev)) AS "adjusted"
        FROM sk
        LEFT JOIN orig ON orig.source_key = sk.source_key
        GROUP BY sk.key
        </script>
        """)
    List<Map<String, Object>> selectOriginalFactAmountsByKeys(@Param("period") String period,
                                                               @Param("factType") String factType,
                                                               @Param("keys") Collection<String> keys);

    /**
     * 判断业务键 ACTIVE 事实的 source_key 链是否存在 REVERSED 行（「是否已调整」判定用，不限期间）。
     * <p>
     * 口径：以各业务键当前 ACTIVE 事实的 source_key 集合为准，任一链上存在
     * REVERSED 行即计入；调用方以返回值 &gt; 0 判定「已调整」（2026-10-15 结佣发起闸门用）。
     *
     * @param factType 事实口径
     * @param keys     合同号/订单号业务键集合（不可为空，空集合由调用方拦截不发起 SQL）
     * @return 存在 REVERSED 行的链数；0 = 从未调整
     */
    @Select("""
        <script>
        WITH sk AS (
            SELECT DISTINCT f.source_key
            FROM (VALUES
              <foreach collection="keys" item="bk" separator=",">(#{bk})</foreach>
            ) AS k(key)
            JOIN pj_perf_fact f ON f.fact_status = 'ACTIVE'
                               AND f.fact_type = #{factType}
                               AND (f.contract_no = k.key OR f.order_no = k.key)
            WHERE f.source_key IS NOT NULL
        )
        SELECT COUNT(*)
        FROM sk
        JOIN pj_perf_fact x ON x.source_key = sk.source_key
                           AND x.fact_status = 'REVERSED'
        </script>
        """)
    long existsReversedChainByKeys(@Param("factType") String factType,
                                   @Param("keys") Collection<String> keys);

    /**
     * 判断业务键的调整是否为「实质调整」（调整前金额合计 ≠ 当前 ACTIVE 合计）。
     * <p>
     * 背景（2026-10-29）：贝壳重复导入同额新签时，旧事实被 supersede 为 REVERSED、新事实
     * ACTIVE 但金额一分不差；若仅按 {@link #existsReversedChainByKeys} 判定「已调整」，
     * 会要求实收也做一次无意义的调整，导致同额重复导入的合同永久无法发起结佣
     * （测试环境 7 月 215 个 supersede 订单中 212 个金额完全相同）。
     * <p>
     * 口径：以各业务键当前 ACTIVE 事实的 source_key 链为准，链上最早一条事实金额为「调整前」，
     * 当前 ACTIVE 金额合计为「调整后」；两者不等即实质调整。新增角色人链（MANUAL-ADJ /
     * MANUAL-CADJ）调整前按 0 计（与 {@link #selectOriginalFactAmountsByKeys} 口径一致）。
     *
     * @param factType 事实口径
     * @param keys     合同号/订单号业务键集合（不可为空，空集合由调用方拦截不发起 SQL）
     * @return originalTotal = 调整前合计，activeTotal = 当前合计；两者不等即实质调整
     */
    @Select("""
        <script>
        WITH sk AS (
            SELECT DISTINCT f.source_key
            FROM (VALUES
              <foreach collection="keys" item="bk" separator=",">(#{bk})</foreach>
            ) AS k(key)
            JOIN pj_perf_fact f ON f.fact_status = 'ACTIVE'
                               AND f.fact_type = #{factType}
                               AND (f.contract_no = k.key OR f.order_no = k.key)
            WHERE f.source_key IS NOT NULL
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
            ORDER BY sk.source_key, x.id ASC
        ),
        act AS (
            SELECT sk.source_key, SUM(f.performance_amount) AS amt
            FROM sk
            JOIN pj_perf_fact f ON f.source_key = sk.source_key
                               AND f.fact_status = 'ACTIVE'
                               AND f.fact_type = #{factType}
            GROUP BY sk.source_key
        )
        SELECT COALESCE(SUM(o.amt), 0) AS "originalTotal",
               COALESCE(SUM(a.amt), 0) AS "activeTotal"
        FROM sk
        LEFT JOIN orig o ON o.source_key = sk.source_key
        LEFT JOIN act a ON a.source_key = sk.source_key
        </script>
        """)
    Map<String, Object> selectAdjustAmountTotals(@Param("factType") String factType,
                                                 @Param("keys") Collection<String> keys);

    /**
     * 按期间查询「合同」维度业绩汇总（结佣申请列表合并展示用）。
     * <p>
     * 按业务键聚合：一手房/房产金融/家装荐客以订单号为准（空回退合同号），
     * 其余以合同号为准（空回退订单号）；返回的 contractNo 即业务键，
     * 结佣申请单的存储键与幂等匹配（findActiveApplication / uk_capp_period_order）均以此为准。
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
                     <if test="period != null and period != ''">AND e.period = #{period}</if>
                     AND (CASE WHEN e.biz_type IN ('一手房','房产金融','家装荐客') THEN COALESCE(NULLIF(e.order_no,''), NULLIF(e.contract_no,'')) ELSE COALESCE(NULLIF(e.contract_no,''), NULLIF(e.order_no,'')) END) = s.biz_key
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
            SELECT CASE WHEN f.biz_type IN ('一手房','房产金融','家装荐客') THEN COALESCE(NULLIF(f.order_no,''), NULLIF(f.contract_no,'')) ELSE COALESCE(NULLIF(f.contract_no,''), NULLIF(f.order_no,'')) END AS biz_key,
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
            <if test="period != null and period != ''">AND f.period = #{period}</if>
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
            <if test="keyword != null and keyword != ''">
              AND (f.contract_no LIKE CONCAT('%', #{keyword}::text, '%')
                   OR f.order_no LIKE CONCAT('%', #{keyword}::text, '%')
                   OR f.property_address LIKE CONCAT('%', #{keyword}::text, '%'))
            </if>
        ) s
        GROUP BY s.biz_key
        ORDER BY MAX(s.business_date) DESC, s.biz_key
        </script>
        """)
    List<PerformanceContractSummaryDTO> selectContractSummaries(@Param("period") String period,
                                                                 @Param("factType") String factType,
                                                                 @Param("deptId") Long deptId,
                                                                 @Param("employeeId") Long employeeId,
                                                                 @Param("keyword") String keyword);

    /**
     * 跨合同去重员工数：指定期间/口径下，业务键（合同号或订单号）命中集合的 ACTIVE 事实
     * 中 COUNT(DISTINCT employee_id)（结佣明细列表合计口径，与 selectManageSummary 一致）。
     */
    @Select("""
        <script>
        SELECT COUNT(DISTINCT f.employee_id)
        FROM pj_perf_fact f
        WHERE f.fact_status = 'ACTIVE'
          <if test="period != null and period != ''">AND f.period = #{period}</if>
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
          <if test="period != null and period != ''">AND f.period = #{period}</if>
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
     * 按期间 + 订单号/合同号查询合同基本信息（调整单详情展示用）。
     * <p>
     * 同合同号挂多订单时按订单号精确限定，避免 MAX 聚合把两个订单的
     * 订单号/房源地址/签约时间串行展示；订单号为空（历史调整单）退化原双键 OR 口径。
     *
     * @param period     归属期间
     * @param orderNo    订单号（可空）
     * @param contractNo 合同号（或历史业务键）
     * @return 合同摘要；查不到返回 null
     */
    @Select("""
        <script>
        SELECT COALESCE(MAX(f.contract_no),MAX(f.order_no)) AS "contractNo",
               MAX(f.order_no) AS "orderNo",
               MAX(f.biz_type) AS "bizType",
               MAX(f.property_address) AS "propertyAddress",
               MIN(f.business_date) AS "businessDate"
        FROM pj_perf_fact f
        WHERE f.fact_status = 'ACTIVE'
          <if test="period != null and period != ''">AND f.period = #{period}</if>
          <if test="orderNo != null and orderNo != ''">AND f.order_no = #{orderNo}</if>
          AND (f.contract_no = #{contractNo} OR f.order_no = #{contractNo})
        </script>
        """)
    java.util.Map<String, Object> selectContractInfoByContractNo(
        @Param("period") String period, @Param("orderNo") String orderNo, @Param("contractNo") String contractNo);

    /**
     * 按期间 + 订单号/合同号 + 事实口径查询该订单下全部有效明细（调整单详情展示用）。
     * <p>
     * 只查 ACTIVE 状态的事实，过滤掉已冲销/已替代的历史行。
     * 应收金额与实收金额按 source_key 交叉配对，与业绩明细页口径一致。
     * 同合同号挂多订单时按订单号精确限定（详情明细不跨订单混排）；
     * 订单号为空（历史调整单）退化原双键 OR 口径。
     *
     * @param period     归属期间
     * @param orderNo    订单号（可空）
     * @param contractNo 合同号（或历史业务键）
     * @param factType   事实口径（PERF_EXPECT / PERF_REAL）
     * @return 明细列表
     */
    @Select("""
        <script>
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
          <if test="period != null and period != ''">AND f.period = #{period}</if>
          AND f.fact_type = #{factType}
          <if test="orderNo != null and orderNo != ''">AND f.order_no = #{orderNo}</if>
          AND (f.contract_no = #{contractNo} OR f.order_no = #{contractNo})
        ORDER BY e.employee_name, d.dept_id, f.role_type, f.id
        </script>
        """)
    List<AdjustFactDetailVo> selectAdjustFactDetails(@Param("period") String period,
                                                       @Param("orderNo") String orderNo,
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
            SELECT CASE WHEN f.biz_type IN ('一手房','房产金融','家装荐客') THEN COALESCE(NULLIF(f.order_no,''), NULLIF(f.contract_no,'')) ELSE COALESCE(NULLIF(f.contract_no,''), NULLIF(f.order_no,'')) END AS biz_key,
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
            SELECT CASE WHEN rc.biz_type IN ('一手房','房产金融','家装荐客') THEN COALESCE(NULLIF(rc.order_no,''), NULLIF(rc.contract_no,'')) ELSE COALESCE(NULLIF(rc.contract_no,''), NULLIF(rc.order_no,'')) END,
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
        reversed_real AS (
            -- 实收侧每个 source_key 最早一条 REVERSED rd 金额（实收调整 supersede 链原值）
            SELECT DISTINCT ON (rdr.source_key)
                   rdr.source_key, rdr.performance_amount
            FROM pj_received_detail rdr
            WHERE rdr.detail_status = 'REVERSED'
            <if test="period != null and period != ''">
              AND rdr.period = #{period}
            </if>
            ORDER BY rdr.source_key, rdr.id ASC
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
                   -- 实收原值合计：链上最早 REVERSED 金额；实收加人新人行（MANUAL-RADJ）按 0 计
                   COALESCE(SUM(CASE WHEN ar.src = 'R'
                                     THEN CASE WHEN ar.source_key LIKE '%|MANUAL-RADJ-%' THEN 0
                                               ELSE COALESCE(rr.performance_amount, ar.amount) END
                                     ELSE 0 END), 0) AS "realOriginalAmount",
                   BOOL_OR(ar.src = 'R' AND (rr.source_key IS NOT NULL
                                             OR ar.source_key LIKE '%|MANUAL-RADJ-%')) AS "realAdjusted",
                   BOOL_OR(ar.adjust_id IS NOT NULL) AS "hasAdjust",
                   COUNT(DISTINCT COALESCE(ar.employee_id::text, ar.emp_code)) AS "employeeCount",
                   COUNT(*) AS "detailCount"
            FROM contract_period cp
            JOIN all_rows ar ON ar.biz_key = cp.biz_key
            LEFT JOIN reversed_expect re ON re.source_key = ar.source_key
            LEFT JOIN reversed_real rr ON rr.source_key = ar.source_key AND ar.src = 'R'
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
               fa."realOriginalAmount",
               fa."realAdjusted",
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
        <where>
          <if test="settled != null and settled == true">
            lc.status IN ('APPROVED', 'LOCKED', 'CLOSED')
          </if>
          <if test="settled != null and settled == false">
            (lc.status IS NULL OR lc.status NOT IN ('APPROVED', 'LOCKED', 'CLOSED'))
          </if>
          <!-- 退单三态筛选（合同级已退单口径）：REFUND=存在已执行退单，NORMAL=排除已退单，空=全部 -->
          <if test="refundFilter == 'REFUND'">
            AND EXISTS (
              SELECT 1 FROM pj_perf_refund r
              WHERE r.status = 'EXECUTED'
                AND (r.contract_no = fa."contractNo"
                     OR (r.order_no IS NOT NULL AND r.order_no &lt;&gt; '' AND r.order_no = fa."orderNo"))
            )
          </if>
          <if test="refundFilter == 'NORMAL'">
            AND NOT EXISTS (
              SELECT 1 FROM pj_perf_refund r
              WHERE r.status = 'EXECUTED'
                AND (r.contract_no = fa."contractNo"
                     OR (r.order_no IS NOT NULL AND r.order_no &lt;&gt; '' AND r.order_no = fa."orderNo"))
            )
          </if>
        </where>
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
            @Param("settled") Boolean settled,
            @Param("refundFilter") String refundFilter,
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
            SELECT CASE WHEN f.biz_type IN ('一手房','房产金融','家装荐客') THEN COALESCE(NULLIF(f.order_no,''), NULLIF(f.contract_no,'')) ELSE COALESCE(NULLIF(f.contract_no,''), NULLIF(f.order_no,'')) END AS biz_key,
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
            SELECT CASE WHEN rc.biz_type IN ('一手房','房产金融','家装荐客') THEN COALESCE(NULLIF(rc.order_no,''), NULLIF(rc.contract_no,'')) ELSE COALESCE(NULLIF(rc.contract_no,''), NULLIF(rc.order_no,'')) END,
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
          <if test="settled != null and settled == true">
            AND EXISTS (
              SELECT 1 FROM pj_commission_application ca
              WHERE ca.period = all_rows.period
                AND ca.contract_no IN (all_rows.contract_no, all_rows.order_no)
                AND ca.status IN ('APPROVED', 'LOCKED', 'CLOSED')
            )
          </if>
          <if test="settled != null and settled == false">
            AND NOT EXISTS (
              SELECT 1 FROM pj_commission_application ca
              WHERE ca.period = all_rows.period
                AND ca.contract_no IN (all_rows.contract_no, all_rows.order_no)
                AND ca.status IN ('APPROVED', 'LOCKED', 'CLOSED')
            )
          </if>
          <!-- 退单三态筛选（合同级已退单口径）：REFUND=存在已执行退单，NORMAL=排除已退单，空=全部 -->
          <if test="refundFilter == 'REFUND'">
            AND EXISTS (
              SELECT 1 FROM pj_perf_refund r
              WHERE r.status = 'EXECUTED'
                AND (r.contract_no = all_rows.contract_no
                     OR (r.order_no IS NOT NULL AND r.order_no &lt;&gt; '' AND r.order_no = all_rows.order_no))
            )
          </if>
          <if test="refundFilter == 'NORMAL'">
            AND NOT EXISTS (
              SELECT 1 FROM pj_perf_refund r
              WHERE r.status = 'EXECUTED'
                AND (r.contract_no = all_rows.contract_no
                     OR (r.order_no IS NOT NULL AND r.order_no &lt;&gt; '' AND r.order_no = all_rows.order_no))
            )
          </if>
        </script>
        """)
    long countFactSearchByContract(
            @Param("period") String period,
            @Param("deptId") Long deptId,
            @Param("bizType") String bizType,
            @Param("keyword") String keyword,
            @Param("employeeId") Long employeeId,
            @Param("settled") Boolean settled,
            @Param("refundFilter") String refundFilter);

    /**
     * 完整业绩查询的全量汇总（汇总条）：三个指标「各按自身归属月」独立统计，不再与列表同口径
     * （列表行仍为合同全周期聚合，汇总条只看所选月份的事实）：
     * <ul>
     *   <li>新签合计：新签月（{@code pj_perf_fact.period}，ACTIVE PERF_EXPECT）；</li>
     *   <li>实收合计：实收月（{@code pj_received_detail.period}，ACTIVE 明细）；</li>
     *   <li>结佣合计：结佣月（{@code pj_commission_item.period}＝业绩归属月，文档明确非
     *       approved_month；REVERSED 明细永久保留但不计入）。</li>
     * </ul>
     * 不分页，按业务类型分组返回三组金额，折算后合计由 Service 按类型系数折算累加。
     * <p>
     * settled 仅作用于结佣这一组（已结佣＝APPROVED 明细；未结佣＝非 APPROVED 明细）；
     * 合同数由调用方以列表 total 提供（不再由本查询返回）。
     */
    @Select("""
        <script>
        WITH t AS (
            -- ① 新签：按新签月（业绩归属月 pj_perf_fact.period）统计
            SELECT f.biz_type AS biz_type,
                   f.performance_amount AS expect_amt,
                   CAST(0 AS numeric) AS real_amt,
                   CAST(0 AS numeric) AS comm_amt
            FROM pj_perf_fact f
            WHERE f.fact_status = 'ACTIVE'
              AND f.fact_type = 'PERF_EXPECT'
              AND COALESCE(f.order_no, f.contract_no) IS NOT NULL
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
              <!-- 退单三态筛选（合同级已退单口径） -->
              <if test="refundFilter == 'REFUND'">
                AND EXISTS (
                  SELECT 1 FROM pj_perf_refund r
                  WHERE r.status = 'EXECUTED'
                    AND (r.contract_no = f.contract_no
                         OR (r.order_no IS NOT NULL AND r.order_no &lt;&gt; '' AND r.order_no = f.order_no))
                )
              </if>
              <if test="refundFilter == 'NORMAL'">
                AND NOT EXISTS (
                  SELECT 1 FROM pj_perf_refund r
                  WHERE r.status = 'EXECUTED'
                    AND (r.contract_no = f.contract_no
                         OR (r.order_no IS NOT NULL AND r.order_no &lt;&gt; '' AND r.order_no = f.order_no))
                )
              </if>
            UNION ALL
            -- ② 实收：按实收月（pj_received_detail.period）统计
            SELECT rc.biz_type,
                   CAST(0 AS numeric),
                   rd.performance_amount,
                   CAST(0 AS numeric)
            FROM pj_received_detail rd
            JOIN pj_received_contract rc ON rc.id = rd.contract_id
            LEFT JOIN pj_people_employee e ON e.employee_code = rd.employee_external_code
            WHERE rd.detail_status = 'ACTIVE'
              AND COALESCE(rc.order_no, rc.contract_no) IS NOT NULL
              <if test="period != null and period != ''">
                AND rd.period = #{period}
              </if>
              <if test="deptId != null">
                AND (COALESCE(rd.dept_id, rc.dept_id) = #{deptId}
                     OR EXISTS (SELECT 1 FROM sys_dept sd
                                WHERE sd.dept_id = COALESCE(rd.dept_id, rc.dept_id)
                                  AND sd.ancestors LIKE CONCAT('%', #{deptId}, '%')))
              </if>
              <if test="employeeId != null">
                AND e.employee_id = #{employeeId}
              </if>
              <if test="bizType != null and bizType != ''">
                AND rc.biz_type = #{bizType}
              </if>
              <if test="keyword != null and keyword != ''">
                AND (rc.contract_no ILIKE CONCAT('%', #{keyword}::text, '%')
                  OR rc.order_no ILIKE CONCAT('%', #{keyword}::text, '%')
                  OR rc.property_address ILIKE CONCAT('%', #{keyword}::text, '%'))
              </if>
              <!-- 退单三态筛选（合同级已退单口径） -->
              <if test="refundFilter == 'REFUND'">
                AND EXISTS (
                  SELECT 1 FROM pj_perf_refund r
                  WHERE r.status = 'EXECUTED'
                    AND (r.contract_no = rc.contract_no
                         OR (r.order_no IS NOT NULL AND r.order_no &lt;&gt; '' AND r.order_no = rc.order_no))
                )
              </if>
              <if test="refundFilter == 'NORMAL'">
                AND NOT EXISTS (
                  SELECT 1 FROM pj_perf_refund r
                  WHERE r.status = 'EXECUTED'
                    AND (r.contract_no = rc.contract_no
                         OR (r.order_no IS NOT NULL AND r.order_no &lt;&gt; '' AND r.order_no = rc.order_no))
                )
              </if>
            UNION ALL
            -- ③ 结佣：按结佣月（业绩归属月 pj_commission_item.period）统计；REVERSED 明细永久保留但不计入
            SELECT ci.biz_type,
                   CAST(0 AS numeric),
                   CAST(0 AS numeric),
                   ci.amount
            FROM pj_commission_item ci
            WHERE ci.status &lt;&gt; 'REVERSED'
              AND COALESCE(ci.order_no, ci.contract_no) IS NOT NULL
              <if test="period != null and period != ''">
                AND ci.period = #{period}
              </if>
              <if test="deptId != null">
                AND (ci.dept_id = #{deptId}
                     OR EXISTS (SELECT 1 FROM sys_dept sd WHERE sd.dept_id = ci.dept_id
                                AND sd.ancestors LIKE CONCAT('%', #{deptId}, '%')))
              </if>
              <if test="employeeId != null">
                AND ci.employee_id = #{employeeId}
              </if>
              <if test="bizType != null and bizType != ''">
                AND ci.biz_type = #{bizType}
              </if>
              <if test="keyword != null and keyword != ''">
                AND (ci.contract_no ILIKE CONCAT('%', #{keyword}::text, '%')
                  OR ci.order_no ILIKE CONCAT('%', #{keyword}::text, '%')
                  OR ci.property_address ILIKE CONCAT('%', #{keyword}::text, '%'))
              </if>
              <if test="settled != null and settled == true">
                AND ci.status = 'APPROVED'
              </if>
              <if test="settled != null and settled == false">
                AND ci.status &lt;&gt; 'APPROVED'
              </if>
              <!-- 退单三态筛选（合同级已退单口径） -->
              <if test="refundFilter == 'REFUND'">
                AND EXISTS (
                  SELECT 1 FROM pj_perf_refund r
                  WHERE r.status = 'EXECUTED'
                    AND (r.contract_no = ci.contract_no
                         OR (r.order_no IS NOT NULL AND r.order_no &lt;&gt; '' AND r.order_no = ci.order_no))
                )
              </if>
              <if test="refundFilter == 'NORMAL'">
                AND NOT EXISTS (
                  SELECT 1 FROM pj_perf_refund r
                  WHERE r.status = 'EXECUTED'
                    AND (r.contract_no = ci.contract_no
                         OR (r.order_no IS NOT NULL AND r.order_no &lt;&gt; '' AND r.order_no = ci.order_no))
                )
              </if>
        )
        SELECT t.biz_type AS "bizType",
               COALESCE(SUM(t.expect_amt), 0) AS "totalExpectAmount",
               COALESCE(SUM(t.real_amt), 0) AS "totalRealAmount",
               COALESCE(SUM(t.comm_amt), 0) AS "totalCommissionAmount"
        FROM t
        GROUP BY t.biz_type
        </script>
        """)
    List<Map<String, Object>> selectFactSearchSummary(
            @Param("period") String period,
            @Param("deptId") Long deptId,
            @Param("bizType") String bizType,
            @Param("keyword") String keyword,
            @Param("employeeId") Long employeeId,
            @Param("settled") Boolean settled,
            @Param("refundFilter") String refundFilter);

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
               COALESCE(
                 -- 配对实收行的链原值合计（最早 REVERSED 金额；MANUAL-RADJ 新人行按 0），
                 -- 与 realAmount 不一致时前端展示「原值 → 调整后值」
                 (SELECT SUM(CASE WHEN rdx.source_key LIKE '%|MANUAL-RADJ-%' THEN 0
                                  ELSE COALESCE((
                                      SELECT rdr.performance_amount
                                      FROM pj_received_detail rdr
                                      WHERE rdr.source_key = rdx.source_key
                                        AND rdr.detail_status = 'REVERSED'
                                      ORDER BY rdr.id ASC LIMIT 1), rdx.performance_amount) END)
                  FROM pj_received_detail rdx
                  JOIN pj_received_contract rc ON rc.id = rdx.contract_id
                  WHERE rdx.detail_status = 'ACTIVE'
                    AND rdx.period = f.period
                    AND (rc.order_no = f.order_no OR rc.contract_no = f.contract_no
                         OR rc.order_no = f.contract_no OR rc.contract_no = f.order_no)
                    AND rdx.employee_external_code IS NOT DISTINCT FROM f.employee_external_code
                    AND rdx.role_type IS NOT DISTINCT FROM f.role_type),
                 0
               ) AS "originalRealAmount",
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
        <script>
        SELECT f2.biz_type
        FROM pj_perf_fact f2
        WHERE f2.fact_status = 'ACTIVE'
          <if test="period != null and period != ''">AND f2.period = #{period}</if>
          AND f2.fact_type = #{factType}
          AND (f2.contract_no = #{contractNo} OR f2.order_no = #{contractNo})
        LIMIT 1
        </script>
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

    /**
     * 业绩汇总分页：按期间维度（月/季/年）+ 员工聚合新签业绩（PERF_EXPECT ACTIVE）。
     * <p>
     * periodType 决定期间标签：MONTH→YYYY-MM、QUARTER→YYYY-Qn、YEAR→YYYY。
     * quarter 仅 periodType=QUARTER 时生效，过滤单季。
     *
     * @param periodType 统计维度 MONTH/QUARTER/YEAR
     * @param year       年份 YYYY（必填）
     * @param quarter    季度 1-4（可选，仅 QUARTER 生效）
     * @param deptId     部门 ID（含子部门；null 不限）
     * @param employeeId 员工 ID（经纪人本人；null 不限）
     * @param bizType    业务类型（可选）
     * @param offset     偏移
     * @param pageSize    每页大小
     */
    @Select("""
        <script>
        WITH labeled AS (
            SELECT
                CASE
                    WHEN #{periodType} = 'MONTH' THEN f.period
                    WHEN #{periodType} = 'QUARTER' THEN SUBSTRING(f.period, 1, 4) || '-Q' || CAST((CAST(SUBSTRING(f.period, 6, 2) AS INT) - 1) / 3 + 1 AS VARCHAR)
                    ELSE SUBSTRING(f.period, 1, 4)
                END AS period_label,
                f.employee_id, f.dept_id, f.biz_type, f.contract_no, f.order_no, f.performance_amount
            FROM pj_perf_fact f
            WHERE f.fact_status = 'ACTIVE'
              AND f.fact_type = 'PERF_EXPECT'
              AND f.period LIKE #{year}::text || '-%'
              <if test="quarter != null">
                AND (CAST(SUBSTRING(f.period, 6, 2) AS INT) - 1) / 3 + 1 = #{quarter}
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
        )
        SELECT l.period_label AS "period",
               l.employee_id AS "employeeId",
               e.employee_name AS "employeeName",
               e.employee_code AS "employeeCode",
               d.dept_name AS "deptName",
               l.dept_id AS "deptId",
               COUNT(DISTINCT CASE WHEN l.biz_type IN ('一手房','房产金融','家装荐客') THEN COALESCE(NULLIF(l.order_no,''), NULLIF(l.contract_no,'')) ELSE COALESCE(NULLIF(l.contract_no,''), NULLIF(l.order_no,'')) END) AS "contractCount",
               COALESCE(SUM(l.performance_amount), 0) AS "totalAmount"
        FROM labeled l
        LEFT JOIN pj_people_employee e ON e.employee_id = l.employee_id
        LEFT JOIN sys_dept d ON d.dept_id = l.dept_id
        GROUP BY l.period_label, l.employee_id, e.employee_name, e.employee_code, d.dept_name, l.dept_id
        ORDER BY l.period_label DESC, "totalAmount" DESC
        LIMIT #{pageSize} OFFSET #{offset}
        </script>
        """)
    List<PerformanceSummaryVo> selectSummaryPage(
            @Param("periodType") String periodType,
            @Param("year") String year,
            @Param("quarter") Integer quarter,
            @Param("deptId") Long deptId,
            @Param("employeeId") Long employeeId,
            @Param("bizType") String bizType,
            @Param("offset") long offset,
            @Param("pageSize") int pageSize);

    /**
     * 业绩汇总行数（分页 total，按期间维度 + 员工去重）。
     * <p>
     * 条件口径与 {@link #selectSummaryPage} 完全一致。
     */
    @Select("""
        <script>
        SELECT COUNT(*) FROM (
            SELECT 1
            FROM pj_perf_fact f
            WHERE f.fact_status = 'ACTIVE'
              AND f.fact_type = 'PERF_EXPECT'
              AND f.period LIKE #{year}::text || '-%'
              <if test="quarter != null">
                AND (CAST(SUBSTRING(f.period, 6, 2) AS INT) - 1) / 3 + 1 = #{quarter}
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
            GROUP BY
                CASE
                    WHEN #{periodType} = 'MONTH' THEN f.period
                    WHEN #{periodType} = 'QUARTER' THEN SUBSTRING(f.period, 1, 4) || '-Q' || CAST((CAST(SUBSTRING(f.period, 6, 2) AS INT) - 1) / 3 + 1 AS VARCHAR)
                    ELSE SUBSTRING(f.period, 1, 4)
                END,
                f.employee_id
        ) t
        </script>
        """)
    long countSummary(
            @Param("periodType") String periodType,
            @Param("year") String year,
            @Param("quarter") Integer quarter,
            @Param("deptId") Long deptId,
            @Param("employeeId") Long employeeId,
            @Param("bizType") String bizType);

    /**
     * 业绩排行分页：按员工聚合新签业绩（PERF_EXPECT ACTIVE），金额降序。
     * <p>
     * quarter 仅 periodType=QUARTER 时生效，过滤单季；month 仅 periodType=MONTH 时生效，过滤单月；
     * periodType 仅供 service 层校验，SQL 本身按年份（+可选季度/月份）聚合，
     * 与维度无关（行 = 员工，金额 = 该期间新签合计）。
     *
     * @param year     年份 YYYY（必填）
     * @param quarter  季度 1-4（可选）
     * @param month    月份 1-12（可选）
     * @param deptId   部门 ID（含子部门；null 不限）
     * @param bizType  业务类型（可选）
     * @param offset   偏移
     * @param pageSize  每页大小
     */
    @Select("""
        <script>
        SELECT f.employee_id AS "employeeId",
               e.employee_name AS "employeeName",
               e.employee_code AS "employeeCode",
               d.dept_name AS "deptName",
               f.dept_id AS "deptId",
               COUNT(DISTINCT CASE WHEN f.biz_type IN ('一手房','房产金融','家装荐客') THEN COALESCE(NULLIF(f.order_no,''), NULLIF(f.contract_no,'')) ELSE COALESCE(NULLIF(f.contract_no,''), NULLIF(f.order_no,'')) END) AS "contractCount",
               COALESCE(SUM(f.performance_amount), 0) AS "totalAmount"
        FROM pj_perf_fact f
        LEFT JOIN pj_people_employee e ON e.employee_id = f.employee_id
        LEFT JOIN sys_dept d ON d.dept_id = f.dept_id
        WHERE f.fact_status = 'ACTIVE'
          AND f.fact_type = 'PERF_EXPECT'
          AND f.period LIKE #{year}::text || '-%'
          <if test="quarter != null">
            AND (CAST(SUBSTRING(f.period, 6, 2) AS INT) - 1) / 3 + 1 = #{quarter}
          </if>
          <if test="month != null">
            AND CAST(SUBSTRING(f.period, 6, 2) AS INT) = #{month}
          </if>
          <if test="deptId != null">
            AND (f.dept_id = #{deptId}
                 OR EXISTS (SELECT 1 FROM sys_dept sd WHERE sd.dept_id = f.dept_id
                            AND sd.ancestors LIKE CONCAT('%', #{deptId}, '%')))
          </if>
          <if test="bizType != null and bizType != ''">
            AND f.biz_type = #{bizType}
          </if>
          <if test="employeeId != null">
            AND f.employee_id = #{employeeId}
          </if>
        GROUP BY f.employee_id, e.employee_name, e.employee_code, d.dept_name, f.dept_id
        ORDER BY "totalAmount" DESC
        LIMIT #{pageSize} OFFSET #{offset}
        </script>
        """)
    List<PerformanceRankVo> selectRankPage(
            @Param("year") String year,
            @Param("quarter") Integer quarter,
            @Param("month") Integer month,
            @Param("deptId") Long deptId,
            @Param("bizType") String bizType,
            @Param("employeeId") Long employeeId,
            @Param("offset") long offset,
            @Param("pageSize") int pageSize);

    /**
     * 业绩排行行数（分页 total，按员工去重）。
     * <p>
     * 条件口径与 {@link #selectRankPage} 完全一致。
     */
    @Select("""
        <script>
        SELECT COUNT(DISTINCT f.employee_id)
        FROM pj_perf_fact f
        WHERE f.fact_status = 'ACTIVE'
          AND f.fact_type = 'PERF_EXPECT'
          AND f.period LIKE #{year}::text || '-%'
          <if test="quarter != null">
            AND (CAST(SUBSTRING(f.period, 6, 2) AS INT) - 1) / 3 + 1 = #{quarter}
          </if>
          <if test="month != null">
            AND CAST(SUBSTRING(f.period, 6, 2) AS INT) = #{month}
          </if>
          <if test="deptId != null">
            AND (f.dept_id = #{deptId}
                 OR EXISTS (SELECT 1 FROM sys_dept sd WHERE sd.dept_id = f.dept_id
                            AND sd.ancestors LIKE CONCAT('%', #{deptId}, '%')))
          </if>
          <if test="bizType != null and bizType != ''">
            AND f.biz_type = #{bizType}
          </if>
          <if test="employeeId != null">
            AND f.employee_id = #{employeeId}
          </if>
        </script>
        """)
    long countRank(
            @Param("year") String year,
            @Param("quarter") Integer quarter,
            @Param("month") Integer month,
            @Param("deptId") Long deptId,
            @Param("bizType") String bizType,
            @Param("employeeId") Long employeeId);

    /**
     * 按员工 + 期间区间逐月汇总 ACTIVE 业绩事实原始金额（职级晋升累计业绩用）。
     * <p>口径：pj_perf_fact.performance_amount 原样求和（不折算、不按 factType 过滤），
     * period 为 YYYY-MM 字符串，闭区间 [fromPeriod, toPeriod]。
     *
     * @param employeeIds 员工 ID 集合
     * @param fromPeriod  起始期间 YYYY-MM（含）
     * @param toPeriod    截止期间 YYYY-MM（含）
     * @return 逐行 employeeId / period / totalAmount（无事实的员工月份不出行）
     */
    @Select("""
        <script>
        SELECT f.employee_id AS "employeeId",
               f.period AS "period",
               COALESCE(SUM(f.performance_amount), 0) AS "totalAmount"
        FROM pj_perf_fact f
        WHERE f.fact_status = 'ACTIVE'
          AND f.period &gt;= #{fromPeriod}
          AND f.period &lt;= #{toPeriod}
          AND f.employee_id IN
          <foreach collection="employeeIds" item="eid" open="(" separator="," close=")">
              #{eid}
          </foreach>
        GROUP BY f.employee_id, f.period
        </script>
        """)
    List<Map<String, Object>> sumActiveByEmployeePeriods(@Param("employeeIds") Collection<Long> employeeIds,
                                                         @Param("fromPeriod") String fromPeriod,
                                                         @Param("toPeriod") String toPeriod);

    /**
     * 业务键（合同号/订单号）是否已有<b>导入生成</b>的退单红冲负数事实（ACTIVE）。
     * <p>
     * 用于手工退单互斥（2026-10-25 定稿）：贝壳导入的负数行已按原事实镜像冲减过，
     * 再手工退单会重复生成一笔负数，两套负数叠加导致合同净额翻负。
     * 仅统计 {@code source='IMPORT'} 的红冲事实，手工退单自己生成的（source=MANUAL）不计，
     * 避免误拦同一合同再次发起合法的其他批次退单判定。
     *
     * @param contractNo 合同号（可空）
     * @param orderNo    订单号（可空）
     * @return 导入红冲事实条数；0 = 无导入退单
     */
    @Select("""
        <script>
        SELECT COUNT(1)
        FROM pj_perf_fact
        WHERE fact_status = 'ACTIVE'
          AND reversal_type = 'REDINK_REFUND'
          AND source = 'IMPORT'
          AND (
            <choose>
              <when test="contractNo != null and contractNo != ''">
                contract_no = #{contractNo}
                <if test="orderNo != null and orderNo != ''">
                  OR order_no = #{orderNo}
                </if>
              </when>
              <otherwise>
                order_no = #{orderNo}
              </otherwise>
            </choose>
          )
        </script>
        """)
    long countImportRedinkByBizKeys(@Param("contractNo") String contractNo,
                                    @Param("orderNo") String orderNo);
}

