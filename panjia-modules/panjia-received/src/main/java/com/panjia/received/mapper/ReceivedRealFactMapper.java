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
          AND rd.period = #{period}
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
        SELECT """ + SUMMARY_COLUMNS + """
        FROM pj_received_detail rd
        JOIN pj_received_contract rc ON rc.id = rd.contract_id
        LEFT JOIN pj_people_employee e
               ON (e.employee_id = rd.employee_id
                   OR (rd.employee_id IS NULL AND e.employee_code = rd.employee_external_code))
        LEFT JOIN sys_dept fd ON fd.dept_id = COALESCE(rd.dept_id, rc.dept_id, e.dept_id)
        LEFT JOIN pj_perf_received_apply ra ON ra.id = rd.received_apply_id
        WHERE rd.detail_status = 'ACTIVE'
          AND rd.period = #{period}
          AND COALESCE(rd.employee_id, e.employee_id) = #{employeeId}
        ORDER BY rd.id
        """)
    List<PerformanceFactSummaryDTO> selectActiveByEmployee(@Param("period") String period,
                                                           @Param("employeeId") Long employeeId);

    /**
     * 按合同号/订单号查 ACTIVE 实收明细。
     * period 可空：空时查该合同全部期间（结佣发起月与实收月解耦，发起时跨期找实收）。
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
          AND (rc.contract_no = #{contractNo} OR rc.order_no = #{contractNo})
        ORDER BY rd.id
        </script>
        """)
    List<PerformanceFactSummaryDTO> selectActiveByContract(@Param("period") String period,
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
     */
    @Select("""
        <script>
        SELECT MAX(s.contract_no) AS "contractNo",
               MAX(s.order_no) AS "orderNo",
               MAX(s.biz_type) AS "bizType",
               MAX(s.property_address) AS "propertyAddress",
               MAX(s.business_date) AS "businessDate",
               COALESCE(SUM(s.amount), 0) AS "amount",
               COALESCE((
                   SELECT SUM(pe.performance_amount)
                   FROM pj_perf_fact pe
                   WHERE pe.fact_status = 'ACTIVE' AND pe.fact_type = 'PERF_EXPECT'
                     AND (COALESCE(pe.order_no, pe.contract_no)) = s.biz_key
                     AND (
                           (pe.period = #{period} AND EXISTS (
                               SELECT 1 FROM pj_perf_fact pc
                               WHERE pc.fact_status = 'ACTIVE' AND pc.fact_type = 'PERF_EXPECT'
                                 AND pc.period = #{period}
                                 AND pc.performance_amount != 0
                                 AND (COALESCE(pc.order_no, pc.contract_no)) = s.biz_key))
                        OR (pe.period &lt; #{period} AND NOT EXISTS (
                               SELECT 1 FROM pj_perf_fact pc
                               WHERE pc.fact_status = 'ACTIVE' AND pc.fact_type = 'PERF_EXPECT'
                                 AND pc.period = #{period}
                                 AND pc.performance_amount != 0
                                 AND (COALESCE(pc.order_no, pc.contract_no)) = s.biz_key))
                         )
               ), 0) AS "expectedAmount",
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
            <if test="employeeId != null">
              AND COALESCE(rd.employee_id, e.employee_id) = #{employeeId}
            </if>
        ) s
        GROUP BY s.biz_key
        ORDER BY MAX(s.business_date) DESC, s.biz_key
        </script>
        """)
    List<PerformanceContractSummaryDTO> selectContractSummaries(@Param("period") String period,
                                                                @Param("deptId") Long deptId,
                                                                @Param("employeeId") Long employeeId);

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
          AND rd.period = #{period}
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
     * 批量查合同维度「调整前」实收金额合计：以各业务键当前 ACTIVE rd 的 sourceKey 为准，
     * 沿明细链（同 source_key，含历史 REVERSED rd）取 id 最早一条金额求和。
     */
    @Select("""
        <script>
        WITH sk AS (
            SELECT DISTINCT k.key, rd.source_key
            FROM (VALUES
              <foreach collection="keys" item="bk" separator=",">(CAST(#{bk} AS text))</foreach>
            ) AS k(key)
            JOIN pj_received_contract rc ON (rc.contract_no = k.key OR rc.order_no = k.key)
            JOIN pj_received_detail rd ON rd.contract_id = rc.id
                                     AND rd.detail_status = 'ACTIVE'
                                     AND rd.source_key IS NOT NULL
            WHERE rc.period = #{period}
        ),
        orig AS (
            SELECT DISTINCT ON (sk.source_key) sk.source_key, x.performance_amount AS amt
            FROM sk
            JOIN pj_received_detail x ON x.source_key = sk.source_key
            ORDER BY sk.source_key, x.id ASC
        )
        SELECT sk.key AS "bizKey",
               COALESCE(SUM(orig.amt), 0) AS "originalAmount"
        FROM sk
        LEFT JOIN orig ON orig.source_key = sk.source_key
        GROUP BY sk.key
        </script>
        """)
    List<Map<String, Object>> selectOriginalAmountsByKeys(@Param("period") String period,
                                                          @Param("keys") Collection<String> keys);

    /** 历史工资导入批次 ACTIVE 实收明细（结佣 LOCKED 建单用；adjust_id 非空的调整新行不计入批次）。 */
    @Select("""
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
        WHERE rd.period = #{period}
          AND rd.source_batch_id = #{batchId}
          AND rd.detail_status = 'ACTIVE'
          AND rd.adjust_id IS NULL
        ORDER BY rd.id
        """)
    List<HistoryRealFactDTO> selectHistoryByBatch(@Param("period") String period,
                                                  @Param("batchId") Long batchId);

    /** 按期间 + 合同号/订单号查 ACTIVE 实收明细实体（结佣调整/对齐写路径用）。 */
    @Select("""
        SELECT rd.*
        FROM pj_received_detail rd
        JOIN pj_received_contract rc ON rc.id = rd.contract_id
        WHERE rd.detail_status = 'ACTIVE'
          AND rd.period = #{period}
          AND (rc.contract_no = #{contractNo} OR rc.order_no = #{contractNo})
        ORDER BY rd.id
        """)
    List<ReceivedDetail> selectActiveDetailsByContract(@Param("period") String period,
                                                        @Param("contractNo") String contractNo);
}
