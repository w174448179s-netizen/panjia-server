package com.panjia.received.mapper;

import com.panjia.contracts.dto.HistoryRealFactDTO;
import com.panjia.contracts.dto.PerformanceContractSummaryDTO;
import com.panjia.contracts.dto.PerformanceFactSummaryDTO;
import com.panjia.received.domain.ReceivedDetail;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 实收「事实」只读查询 Mapper（PERF_REAL 拆表后的结佣/算薪读出口）。
 * <p>
 * 底表 {@code pj_received_detail rd JOIN pj_received_contract rc}，员工主数据按
 * {@code rd.employee_id} 直连，为空时按 {@code employee_external_code} 关联
 * {@code pj_people_employee} 兜底（导入时 rd.employee_id 暂留空）。
 * 必须放在 com.panjia.received.mapper 包内才能被 ReceivedMybatisConfig 扫描。
 */
@Mapper
public interface ReceivedRealFactMapper {

    String SUMMARY_COLUMNS = """
            rd.id AS "factId",
            'PERF_REAL' AS "factType",
            rd.detail_status AS "factStatus",
            rd.period AS "period",
            rc.business_date AS "businessDate",
            COALESCE(rd.employee_id, e.employee_id) AS "employeeId",
            COALESCE(e.employee_code, rd.employee_external_code) AS "employeeCode",
            e.employee_name AS "employeeName",
            COALESCE(rd.dept_id, rc.dept_id, e.dept_id) AS "deptId",
            fd.dept_name AS "deptName",
            rc.biz_type AS "bizType",
            rd.role_type AS "roleType",
            rd.role_name AS "roleName",
            rd.performance_amount AS "amount",
            rd.source_batch_id AS "batchId",
            rd.normalized_record_id AS "normalizedRecordId",
            rd.source_key AS "sourceKey",
            rd.received_apply_id AS "receivedApplyId",
            ra.status AS "receivedStatus",
            rc.contract_no AS "contractNo",
            rc.order_no AS "orderNo",
            rc.property_address AS "propertyAddress",
            rd.share_ratio AS "shareRatio"
        """;

    String SUMMARY_FROM = """
            FROM pj_received_detail rd
            JOIN pj_received_contract rc ON rc.id = rd.contract_id
            LEFT JOIN pj_people_employee e
                   ON (e.employee_id = rd.employee_id
                       OR (rd.employee_id IS NULL AND e.employee_code = rd.employee_external_code))
            LEFT JOIN sys_dept fd ON fd.dept_id = COALESCE(rd.dept_id, rc.dept_id, e.dept_id)
            LEFT JOIN pj_perf_received_apply ra ON ra.id = rd.received_apply_id
        """;

    /** 按期间 + 门店（含下级）查 ACTIVE 实收明细。 */
    @Select("""
        <script>
        SELECT """ + SUMMARY_COLUMNS + """
        FROM pj_received_detail rd
        JOIN pj_received_contract rc ON rc.id = rd.contract_id
        LEFT JOIN pj_people_employee e
               ON (e.employee_id = rd.employee_id
                   OR (rd.employee_id IS NULL AND e.employee_code = rd.employee_external_code))
        LEFT JOIN sys_dept fd ON fd.dept_id = COALESCE(rd.dept_id, rc.dept_id, e.dept_id)
        LEFT JOIN pj_perf_received_apply ra ON ra.id = rd.received_apply_id
        WHERE rd.detail_status = 'ACTIVE'
          <if test="period != null and period != ''">AND rd.period = #{period}</if>
        <if test="deptId != null">
          AND (COALESCE(rd.dept_id, rc.dept_id, e.dept_id) = #{deptId}
               OR EXISTS (SELECT 1 FROM sys_dept sd
                          WHERE sd.dept_id = COALESCE(rd.dept_id, rc.dept_id, e.dept_id)
                            AND sd.ancestors LIKE CONCAT('%', #{deptId}, '%')))
        </if>
        ORDER BY rd.id
        </script>
        """)
    List<PerformanceFactSummaryDTO> selectActiveByDept(@Param("period") String period,
                                                       @Param("deptId") Long deptId);

    /** 按期间 + 员工查 ACTIVE 实收明细（employee_id 为空时按工号关联兜底）。 */
    @Select("""
        <script>
        SELECT """ + SUMMARY_COLUMNS + """
        FROM pj_received_detail rd
        JOIN pj_received_contract rc ON rc.id = rd.contract_id
        LEFT JOIN pj_people_employee e
               ON (e.employee_id = rd.employee_id
                   OR (rd.employee_id IS NULL AND e.employee_code = rd.employee_external_code))
        LEFT JOIN sys_dept fd ON fd.dept_id = COALESCE(rd.dept_id, rc.dept_id, e.dept_id)
        LEFT JOIN pj_perf_received_apply ra ON ra.id = rd.received_apply_id
        WHERE rd.detail_status = 'ACTIVE'
          <if test="period != null and period != ''">AND rd.period = #{period}</if>
          AND COALESCE(rd.employee_id, e.employee_id) = #{employeeId}
        ORDER BY rd.id
        </script>
        """)
    List<PerformanceFactSummaryDTO> selectActiveByEmployee(@Param("period") String period,
                                                           @Param("employeeId") Long employeeId);

    /**
     * 按订单号/合同号查 ACTIVE 实收明细。
     * period 可空：空时查该合同全部期间（结佣发起月与实收月解耦，发起时跨期找实收）。
     * orderNo 非空时与 contractNo 双键精确限定（同合同号挂多订单防串单）。
     */
    @Select("""
        <script>
        SELECT """ + SUMMARY_COLUMNS + """
        FROM pj_received_detail rd
        JOIN pj_received_contract rc ON rc.id = rd.contract_id
        LEFT JOIN pj_people_employee e
               ON (e.employee_id = rd.employee_id
                   OR (rd.employee_id IS NULL AND e.employee_code = rd.employee_external_code))
        LEFT JOIN sys_dept fd ON fd.dept_id = COALESCE(rd.dept_id, rc.dept_id, e.dept_id)
        LEFT JOIN pj_perf_received_apply ra ON ra.id = rd.received_apply_id
        WHERE rd.detail_status = 'ACTIVE'
          <if test="period != null and period != ''">AND rd.period = #{period}</if>
          <if test="orderNo != null and orderNo != ''">AND rc.order_no = #{orderNo}</if>
          AND (rc.contract_no = #{contractNo} OR rc.order_no = #{contractNo})
        ORDER BY rd.id
        </script>
        """)
    List<PerformanceFactSummaryDTO> selectActiveByContract(@Param("period") String period,
                                                           @Param("orderNo") String orderNo,
                                                           @Param("contractNo") String contractNo);

    /** 按业务键集合（订单号/合同号）跨期间查 ACTIVE 实收明细。 */
    @Select("""
        <script>
        SELECT """ + SUMMARY_COLUMNS + """
        FROM pj_received_detail rd
        JOIN pj_received_contract rc ON rc.id = rd.contract_id
        LEFT JOIN pj_people_employee e
               ON (e.employee_id = rd.employee_id
                   OR (rd.employee_id IS NULL AND e.employee_code = rd.employee_external_code))
        LEFT JOIN sys_dept fd ON fd.dept_id = COALESCE(rd.dept_id, rc.dept_id, e.dept_id)
        LEFT JOIN pj_perf_received_apply ra ON ra.id = rd.received_apply_id
        WHERE rd.detail_status = 'ACTIVE'
          AND (rc.order_no IN
              <foreach collection="bizKeys" item="k" open="(" separator="," close=")">#{k}</foreach>
               OR rc.contract_no IN
              <foreach collection="bizKeys2" item="k" open="(" separator="," close=")">#{k}</foreach>)
        ORDER BY rd.id
        </script>
        """)
    List<PerformanceFactSummaryDTO> selectActiveByBizKeys(@Param("bizKeys") Collection<String> bizKeys,
                                                          @Param("bizKeys2") Collection<String> bizKeys2);

    /** 按实收明细 ID 集合查 ACTIVE 明细。 */
    @Select("""
        <script>
        SELECT """ + SUMMARY_COLUMNS + """
        FROM pj_received_detail rd
        JOIN pj_received_contract rc ON rc.id = rd.contract_id
        LEFT JOIN pj_people_employee e
               ON (e.employee_id = rd.employee_id
                   OR (rd.employee_id IS NULL AND e.employee_code = rd.employee_external_code))
        LEFT JOIN sys_dept fd ON fd.dept_id = COALESCE(rd.dept_id, rc.dept_id, e.dept_id)
        LEFT JOIN pj_perf_received_apply ra ON ra.id = rd.received_apply_id
        WHERE rd.detail_status = 'ACTIVE'
          AND rd.id IN
          <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
        ORDER BY rd.id
        </script>
        """)
    List<PerformanceFactSummaryDTO> selectActiveByIds(@Param("ids") Collection<Long> ids);

    /** 按实收明细 ID 查单条（不限状态，溯源用）。 */
    @Select("""
        SELECT """ + SUMMARY_COLUMNS + """
        FROM pj_received_detail rd
        JOIN pj_received_contract rc ON rc.id = rd.contract_id
        LEFT JOIN pj_people_employee e
               ON (e.employee_id = rd.employee_id
                   OR (rd.employee_id IS NULL AND e.employee_code = rd.employee_external_code))
        LEFT JOIN sys_dept fd ON fd.dept_id = COALESCE(rd.dept_id, rc.dept_id, e.dept_id)
        LEFT JOIN pj_perf_received_apply ra ON ra.id = rd.received_apply_id
        WHERE rd.id = #{id}
        """)
    PerformanceFactSummaryDTO selectByIdAnyStatus(@Param("id") Long id);

    /**
     * 按期间查实收「合同」维度汇总（结佣申请列表合并展示用）。
     * 按 COALESCE(rc.order_no,rc.contract_no) 聚合；deptId 非空含下级部门。
     * <p>period 非空：expectedAmount 取该合同实收月及之前全部期间 ACTIVE PERF_EXPECT 合计
     * （当月+历史合并，冲正镜像不排斥历史正数）；period 可空：空时跨全部期间聚合
     * （仅录合同号不选期间场景），expectedAmount 对应取该合同全部期间 ACTIVE PERF_EXPECT 合计。
     */
    @Select("""
        <script>
        <choose>
            <when test="period != null and period != ''">
        WITH expect_sum AS (
            -- 新签金额口径 = 实收月及之前全部 ACTIVE PERF_EXPECT 合计（当月+历史合并，
            -- 与 loadExpectItemFacts 2026-09-30 定稿一致）：冲正/红冲负数镜像落在当月，
            -- 旧「当月优先排斥历史」会把新签金额算成负数（2026-10-11 修复）
            -- 按员工筛选时仅取该员工的新签明细（员工口径，与实收 amount 同口径）
            SELECT COALESCE(pe.order_no, pe.contract_no) AS biz_key,
                   SUM(pe.performance_amount) AS amt
            FROM pj_perf_fact pe
            WHERE pe.fact_status = 'ACTIVE' AND pe.fact_type = 'PERF_EXPECT'
              AND pe.period &lt;= #{period}
              <if test="employeeId != null">AND pe.employee_id = #{employeeId}</if>
            GROUP BY COALESCE(pe.order_no, pe.contract_no)
        )
            </when>
            <otherwise>
        WITH expect_sum AS (
            SELECT COALESCE(pe.order_no, pe.contract_no) AS biz_key,
                   SUM(pe.performance_amount) AS amt
            FROM pj_perf_fact pe
            WHERE pe.fact_status = 'ACTIVE' AND pe.fact_type = 'PERF_EXPECT'
              <if test="employeeId != null">AND pe.employee_id = #{employeeId}</if>
            GROUP BY COALESCE(pe.order_no, pe.contract_no)
        )
            </otherwise>
        </choose>
        SELECT MAX(s.contract_no) AS "contractNo",
               MAX(s.order_no) AS "orderNo",
               MAX(s.biz_type) AS "bizType",
               MAX(s.property_address) AS "propertyAddress",
               MAX(s.business_date) AS "businessDate",
               MIN(s.period) AS "period",
               COALESCE(SUM(s.amount), 0) AS "amount",
               COALESCE(MAX(es.amt), 0) AS "expectedAmount",
               CASE
                   WHEN bool_or(s.ra_status = 'SUBMITTED') THEN 'SUBMITTED'
                   WHEN bool_or(s.ra_status = 'DRAFT') THEN 'DRAFT'
                   WHEN COUNT(*) = COUNT(s.ra_id) FILTER (WHERE s.ra_status = 'APPROVED') THEN 'APPROVED'
                   ELSE NULL
               END AS "receivedStatus",
               COUNT(DISTINCT s.emp_key) AS "employeeCount",
               COUNT(*) AS "detailCount"
        FROM (
            SELECT COALESCE(rc.order_no, rc.contract_no) AS biz_key,
                   rc.contract_no,
                   rc.order_no,
                   rc.biz_type,
                   rc.property_address,
                   rc.business_date,
                   rd.period AS period,
                   rd.performance_amount AS amount,
                   ra.status AS ra_status,
                   ra.id AS ra_id,
                   COALESCE(rd.employee_id::text, e.employee_id::text, rd.employee_external_code) AS emp_key,
                   COALESCE(rd.dept_id, rc.dept_id, e.dept_id) AS dept_id,
                   COALESCE(rd.employee_id, e.employee_id) AS employee_id
            FROM pj_received_detail rd
            JOIN pj_received_contract rc ON rc.id = rd.contract_id
            LEFT JOIN pj_people_employee e
                   ON (e.employee_id = rd.employee_id
                       OR (rd.employee_id IS NULL AND e.employee_code = rd.employee_external_code))
            LEFT JOIN pj_perf_received_apply ra ON ra.id = rd.received_apply_id
            WHERE rd.detail_status = 'ACTIVE'
            <if test="period != null and period != ''">AND rd.period = #{period}</if>
              AND COALESCE(rc.order_no, rc.contract_no) IS NOT NULL
            <if test="deptId != null">
              AND (COALESCE(rd.dept_id, rc.dept_id, e.dept_id) = #{deptId}
                   OR EXISTS (SELECT 1 FROM sys_dept sd
                              WHERE sd.dept_id = COALESCE(rd.dept_id, rc.dept_id, e.dept_id)
                                AND sd.ancestors LIKE CONCAT('%', #{deptId}, '%')))
            </if>
            <if test="keyword != null and keyword != ''">
              AND (rc.contract_no LIKE CONCAT('%', #{keyword}::text, '%')
                   OR rc.order_no LIKE CONCAT('%', #{keyword}::text, '%')
                   OR rc.property_address LIKE CONCAT('%', #{keyword}::text, '%'))
            </if>
            <if test="employeeId != null">
              AND COALESCE(rd.employee_id, e.employee_id) = #{employeeId}
            </if>
        ) s
        LEFT JOIN expect_sum es ON es.biz_key = s.biz_key
        GROUP BY s.biz_key
        ORDER BY MAX(s.business_date) DESC, s.biz_key
        </script>
        """)
    List<PerformanceContractSummaryDTO> selectContractSummaries(@Param("period") String period,
                                                                @Param("deptId") Long deptId,
                                                                @Param("employeeId") Long employeeId,
                                                                @Param("keyword") String keyword);

    /**
     * 跨合同去重员工数：员工键口径与 {@link #selectContractSummaries} 一致
     * （COALESCE(rd.employee_id, 工号兜底 e.employee_id, rd.employee_external_code)），
     * 业务键命中 rc.contract_no 或 rc.order_no 即计入；仅 ACTIVE 明细。
     */
    @Select("""
        <script>
        SELECT COUNT(DISTINCT COALESCE(rd.employee_id::text, e.employee_id::text, rd.employee_external_code))
        FROM pj_received_detail rd
        JOIN pj_received_contract rc ON rc.id = rd.contract_id
        LEFT JOIN pj_people_employee e
               ON (e.employee_id = rd.employee_id
                   OR (rd.employee_id IS NULL AND e.employee_code = rd.employee_external_code))
        WHERE rd.detail_status = 'ACTIVE'
          <if test="period != null and period != ''">AND rd.period = #{period}</if>
          AND COALESCE(rd.employee_id::text, e.employee_id::text, rd.employee_external_code) IS NOT NULL
          AND (
            rc.contract_no IN
              <foreach collection="keys" item="bk" open="(" separator="," close=")">#{bk}</foreach>
            OR rc.order_no IN
              <foreach collection="keys" item="bk" open="(" separator="," close=")">#{bk}</foreach>
          )
        </script>
        """)
    long selectDistinctEmployeeCountByKeys(@Param("period") String period,
                                           @Param("keys") Collection<String> keys);

    /**
     * 结佣发起页主查询：实收审批通过的审批单行（订单维度，一行=一张审批单，同订单跨期间多行）。
     * 展示字段全部取审批单快照（无需关联实收合同表）；员工数/明细数按 received_apply_id
     * 对 ACTIVE 明细聚合现算（item_count 为建单快照，明细调整后会漂移，不可用）；
     * keyword 命中合同号/订单号/房源地址，bizType 精确匹配；
     * 门店过滤走明细 COALESCE(dd.dept_id, rc.dept_id, e.dept_id)（审批单 dept_id 快照为空），
     * 员工过滤走明细 COALESCE(dd.employee_id, 工号兜底 e.employee_id)。
     */
    @Select("""
        <script>
        SELECT ra.id AS "rowId",
               ra.contract_no AS "contractNo",
               ra.order_no AS "orderNo",
               ra.biz_type AS "bizType",
               ra.property_address AS "propertyAddress",
               ra.business_date AS "businessDate",
               ra.period AS "period",
               'APPROVED' AS "receivedStatus",
               -- 按员工筛选时行金额改为该员工在该审批单下的 ACTIVE 明细合计（员工口径），
               -- 不筛员工时取该审批单 ACTIVE 明细实时合计（含已生效实收调整；快照见 originalAmount）
               -- 注意：不能用 CASE WHEN 参数 IS NOT NULL，PostgreSQL 在参数为 null 时
               -- 无法推断数据类型（could not determine data type of parameter），
               -- 改用 MyBatis choose 动态拼装两套 SQL 分支（注释中勿写井号花括号，MyBatis 会解析为占位符）
               <choose>
                 <when test="employeeId != null">
                   COALESCE((
                       SELECT SUM(dd.performance_amount)
                       FROM pj_received_detail dd
                       LEFT JOIN pj_people_employee e
                              ON (e.employee_id = dd.employee_id
                                  OR (dd.employee_id IS NULL AND e.employee_code = dd.employee_external_code))
                       WHERE dd.received_apply_id = ra.id AND dd.detail_status = 'ACTIVE'
                         AND COALESCE(dd.employee_id, e.employee_id) = #{employeeId}
                   ), 0) AS "amount"
                 </when>
                 <otherwise>
                   COALESCE((
                       SELECT SUM(dd.performance_amount)
                       FROM pj_received_detail dd
                       WHERE dd.received_apply_id = ra.id AND dd.detail_status = 'ACTIVE'
                   ), ra.received_amount) AS "amount"
                 </otherwise>
               </choose>,
               -- 调整前实收合计：审批单 received_amount 快照（审批通过时点值；
               -- 实收调整执行后快照不回头改，与实时 amount 的差即实收调整带来的变化）
               ra.received_amount AS "originalAmount",
               agg.detail_count AS "detailCount",
               agg.employee_count AS "employeeCount"
        FROM pj_perf_received_apply ra
        LEFT JOIN (
            SELECT dd.received_apply_id,
                   COUNT(*) AS detail_count,
                   COUNT(DISTINCT COALESCE(dd.employee_id::text, dd.employee_external_code)) AS employee_count
            FROM pj_received_detail dd
            WHERE dd.detail_status = 'ACTIVE'
            GROUP BY dd.received_apply_id
        ) agg ON agg.received_apply_id = ra.id
        WHERE ra.status = 'APPROVED'
        <if test="keyword != null and keyword != ''">
          AND (ra.contract_no LIKE CONCAT('%', #{keyword}::text, '%')
               OR ra.order_no LIKE CONCAT('%', #{keyword}::text, '%')
               OR ra.property_address LIKE CONCAT('%', #{keyword}::text, '%'))
        </if>
        <if test="bizType != null and bizType != ''">AND ra.biz_type = #{bizType}</if>
        <if test="deptId != null">
          AND EXISTS (
              SELECT 1 FROM pj_received_detail dd
              JOIN pj_received_contract rc ON rc.id = dd.contract_id
              LEFT JOIN pj_people_employee e
                     ON (e.employee_id = dd.employee_id
                         OR (dd.employee_id IS NULL AND e.employee_code = dd.employee_external_code))
              WHERE dd.received_apply_id = ra.id AND dd.detail_status = 'ACTIVE'
                AND (COALESCE(dd.dept_id, rc.dept_id, e.dept_id) = #{deptId}
                     OR EXISTS (SELECT 1 FROM sys_dept sd
                                WHERE sd.dept_id = COALESCE(dd.dept_id, rc.dept_id, e.dept_id)
                                  AND sd.ancestors LIKE CONCAT('%', #{deptId}, '%'))))
        </if>
        <if test="employeeId != null">
          AND EXISTS (
              SELECT 1 FROM pj_received_detail dd
              LEFT JOIN pj_people_employee e
                     ON (e.employee_id = dd.employee_id
                         OR (dd.employee_id IS NULL AND e.employee_code = dd.employee_external_code))
              WHERE dd.received_apply_id = ra.id AND dd.detail_status = 'ACTIVE'
                AND COALESCE(dd.employee_id, e.employee_id) = #{employeeId})
        </if>
        ORDER BY ra.business_date DESC, ra.id DESC
        </script>
        """)
    List<PerformanceContractSummaryDTO> selectApprovedApplies(@Param("deptId") Long deptId,
                                                              @Param("employeeId") Long employeeId,
                                                              @Param("keyword") String keyword,
                                                              @Param("bizType") String bizType);

    /**
     * 批量查合同维度「调整前」实收金额合计：以各业务键当前 ACTIVE rd 的 sourceKey 为准，
     * 沿明细链（同 source_key，含历史 REVERSED rd）取 id 最早一条金额求和。
     * <p>
     * 实收调整「增加角色人」例外：新人行是凭空新增的链（source_key 含 MANUAL-RADJ 标记，
     * 2026-10-14 三类型重构），调整前该角色在合同上不存在，其链按 0 计入原额；
     * 否则新人金额会被同时计入「原值」与「现值」（钱只是从既有角色人转到新人，
     * 业绩调整合同总额不变），虚增一个新人金额。
     * <p>
     * 同时输出 {@code adjusted} 标记：链上存在 REVERSED 或为 MANUAL-RADJ 新人行即「已调整」，
     * 供列表在「原值=现值」（如 AMOUNT 类总额不变的重分配）时仍能标注调整发生。
     */
    @Select("""
        <script>
        WITH k(key) AS (VALUES
          <foreach collection="keys" item="bk" separator=",">(CAST(#{bk} AS text))</foreach>
        ),
        hit AS (
            SELECT rc.id, rc.contract_no, rc.order_no, rc.period
            FROM k JOIN pj_received_contract rc ON rc.order_no = k.key
            UNION
            SELECT rc.id, rc.contract_no, rc.order_no, rc.period
            FROM k JOIN pj_received_contract rc ON rc.contract_no = k.key
        ),
        sk AS (
            SELECT h.order_no AS key, rd.source_key
            FROM hit h
            JOIN pj_received_detail rd ON rd.contract_id = h.id
                                     AND rd.detail_status = 'ACTIVE'
                                     AND rd.source_key IS NOT NULL
            WHERE h.order_no IS NOT NULL
            <if test="period != null and period != ''">AND h.period = #{period}</if>
            UNION
            SELECT h.contract_no AS key, rd.source_key
            FROM hit h
            JOIN pj_received_detail rd ON rd.contract_id = h.id
                                     AND rd.detail_status = 'ACTIVE'
                                     AND rd.source_key IS NOT NULL
            WHERE h.contract_no IS NOT NULL
            <if test="period != null and period != ''">AND h.period = #{period}</if>
        ),
        orig AS (
            SELECT DISTINCT ON (sk.source_key) sk.source_key,
                   CASE WHEN sk.source_key LIKE '%|MANUAL-RADJ-%' THEN 0
                        ELSE x.performance_amount END AS amt
            FROM sk
            JOIN pj_received_detail x ON x.source_key = sk.source_key
            ORDER BY sk.source_key, x.id ASC
        ),
        rev AS (
            SELECT DISTINCT sk2.source_key
            FROM sk sk2
            JOIN pj_received_detail y ON y.source_key = sk2.source_key
                                     AND y.detail_status = 'REVERSED'
        )
        SELECT sk.key AS "bizKey",
               COALESCE(SUM(orig.amt), 0) AS "originalAmount",
               -- 链上存在 REVERSED（supersede 冲销）或为实收加人新人行（MANUAL-RADJ）即「已调整」；
               -- AMOUNT 类调整总额不变时原值=当前值，仅靠金额比较会漏标，故以链存在性为准
               BOOL_OR(sk.source_key LIKE '%|MANUAL-RADJ-%'
                       OR sk.source_key IN (SELECT source_key FROM rev)) AS "adjusted"
        FROM sk
        LEFT JOIN orig ON orig.source_key = sk.source_key
        GROUP BY sk.key
        </script>
        """)
    List<Map<String, Object>> selectOriginalAmountsByKeys(@Param("period") String period,
                                                          @Param("keys") Collection<String> keys);

    /**
     * 判断业务键 ACTIVE 实收明细的 source_key 链是否存在 REVERSED 行
     * （「实收是否已调整」判定用，不限归属期间，2026-10-15 结佣发起闸门用）。
     * <p>
     * 口径：以各业务键当前 ACTIVE rd 的 source_key 集合为准，任一链上存在
     * REVERSED 明细即计入；调用方以返回值 &gt; 0 判定「已调整」。
     *
     * @param keys 合同号/订单号业务键集合（不可为空，空集合由调用方拦截不发起 SQL）
     * @return 存在 REVERSED 行的链数；0 = 从未调整
     */
    @Select("""
        <script>
        WITH k(key) AS (VALUES
          <foreach collection="keys" item="bk" separator=",">(CAST(#{bk} AS text))</foreach>
        ),
        hit AS (
            SELECT rc.id
            FROM k JOIN pj_received_contract rc ON rc.order_no = k.key
            UNION
            SELECT rc.id
            FROM k JOIN pj_received_contract rc ON rc.contract_no = k.key
        ),
        sk AS (
            SELECT DISTINCT rd.source_key
            FROM hit h
            JOIN pj_received_detail rd ON rd.contract_id = h.id
                                     AND rd.detail_status = 'ACTIVE'
                                     AND rd.source_key IS NOT NULL
        )
        SELECT COUNT(*)
        FROM sk
        JOIN pj_received_detail x ON x.source_key = sk.source_key
                                 AND x.detail_status = 'REVERSED'
        </script>
        """)
    long existsReversedChainByKeys(@Param("keys") Collection<String> keys);

    /** 历史工资导入批次 ACTIVE 实收明细（结佣 LOCKED 建单用；adjust_id 非空的调整新行不计入批次）。 */
    @Select("""
        <script>
        SELECT rd.id AS "factId",
               rc.order_no AS "orderNo",
               rc.contract_no AS "contractNo",
               rd.source_key AS "sourceKey",
               rc.property_address AS "propertyAddress",
               rc.business_date AS "businessDate",
               rc.biz_type AS "bizType",
               COALESCE(rd.dept_id, rc.dept_id, e.dept_id) AS "deptId",
               COALESCE(rd.employee_id, e.employee_id) AS "employeeId",
               rd.employee_external_code AS "employeeCode",
               rd.role_type AS "roleType",
               rd.role_name AS "roleName",
               rd.fee_item AS "feeItem",
               rd.performance_amount AS "amount",
               rd.share_ratio AS "shareRatio",
               rd.source_batch_id AS "batchId",
               rd.received_apply_id AS "receivedApplyId"
        FROM pj_received_detail rd
        JOIN pj_received_contract rc ON rc.id = rd.contract_id
        LEFT JOIN pj_people_employee e
               ON (e.employee_id = rd.employee_id
                   OR (rd.employee_id IS NULL AND e.employee_code = rd.employee_external_code))
        WHERE rd.source_batch_id = #{batchId}
          AND rd.detail_status = 'ACTIVE'
          AND rd.adjust_id IS NULL
          <if test="period != null and period != ''">AND rd.period = #{period}</if>
        ORDER BY rd.id
        </script>
        """)
    List<HistoryRealFactDTO> selectHistoryByBatch(@Param("period") String period,
                                                  @Param("batchId") Long batchId);

    /** 按期间 + 订单号/合同号查 ACTIVE 实收明细实体（实收调整/对齐写路径用）；orderNo 非空双键精确限定。 */
    @Select("""
        <script>
        SELECT rd.*
        FROM pj_received_detail rd
        JOIN pj_received_contract rc ON rc.id = rd.contract_id
        WHERE rd.detail_status = 'ACTIVE'
          <if test="period != null and period != ''">AND rd.period = #{period}</if>
          <if test="orderNo != null and orderNo != ''">AND rc.order_no = #{orderNo}</if>
          AND (rc.contract_no = #{contractNo} OR rc.order_no = #{contractNo})
        ORDER BY rd.id
        </script>
        """)
    List<ReceivedDetail> selectActiveDetailsByContract(@Param("period") String period,
                                                        @Param("orderNo") String orderNo,
                                                        @Param("contractNo") String contractNo);

    /**
     * 取合同下某员工的新签（PERF_EXPECT）事实镜像字段。
     * <p>
     * 业务规则：合同无实收时联动建的虚拟实收 = <b>新签的全字段镜像</b>，仅金额不同
     * （金额 = 该人新签变动 delta）。故壳级 biz_type/business_date/dept_id/property_address、
     * 明细级 employee_code/dept_id/role_type/role_name/share_ratio 均回源新签事实。
     * employeeId 非空时精确取该员工的行；号源口径与实收一致（contractNo 可能传订单号）。
     */
    @Select("""
        <script>
        SELECT f.id AS "factId",
               'PERF_EXPECT' AS "factType",
               f.fact_status AS "factStatus",
               f.period AS "period",
               f.business_date AS "businessDate",
               f.employee_id AS "employeeId",
               f.employee_external_code AS "employeeCode",
               f.dept_id AS "deptId",
               f.biz_type AS "bizType",
               f.role_type AS "roleType",
               f.role_name AS "roleName",
               f.performance_amount AS "amount",
               f.share_ratio AS "shareRatio",
               f.contract_no AS "contractNo",
               f.order_no AS "orderNo",
               f.property_address AS "propertyAddress"
        FROM pj_perf_fact f
        WHERE f.fact_type = 'PERF_EXPECT'
          <if test="period != null and period != ''">AND f.period = #{period}</if>
          <if test="employeeId != null">AND f.employee_id = #{employeeId}</if>
          AND (
          <choose>
            <when test="orderNo != null and orderNo != ''">
              f.order_no = #{orderNo}
              <if test="contractNo != null and contractNo != ''">AND f.contract_no = #{contractNo}</if>
            </when>
            <when test="contractNo != null and contractNo != ''">
              (f.contract_no = #{contractNo} OR f.order_no = #{contractNo})
            </when>
            <otherwise>1 = 0</otherwise>
          </choose>
          )
        ORDER BY f.id
        LIMIT 1
        </script>
        """)
    PerformanceFactSummaryDTO selectExpectMirror(@Param("orderNo") String orderNo,
                                                 @Param("contractNo") String contractNo,
                                                 @Param("period") String period,
                                                 @Param("employeeId") Long employeeId);
}
