package com.panjia.contracts.port;

import com.panjia.contracts.dto.HistoryRealFactDTO;
import com.panjia.contracts.dto.PerformanceContractSummaryDTO;
import com.panjia.contracts.dto.PerformanceFactSummaryDTO;
import com.panjia.contracts.dto.ReceivedAlignmentResultDTO;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 实收「事实」跨域端口（received 域对外契约）。
 * <p>
 * PERF_REAL（实收/结佣业绩）拆表后物理落在实收域
 * {@code pj_received_detail(rd) + pj_received_contract(rc)}：
 * panjia-performance 的 {@code CommissionPerformanceAdapter} 对所有 PERF_REAL
 * 读/写一律委托本端口实现（{@code ReceivedRealFactAdapter}），
 * <b>pj_perf_fact 不再承载任何 PERF_REAL 运行时读写</b>。
 * <p>
 * ID 约定：本端口返回事实的 {@code factId} 即 {@code pj_received_detail.id}；
 * 结佣明细 {@code performance_fact_id} 对实收口径绑定的就是该 ID。
 * 口径：findActive* 只返回 {@code detail_status='ACTIVE'}；getById 不限状态（溯源用）。
 */
public interface ReceivedRealFactPort {

    /** 按期间 + 门店查 ACTIVE 实收明细（deptId 取 COALESCE(rd.dept_id,rc.dept_id)，含下级部门）。 */
    List<PerformanceFactSummaryDTO> findActiveByDept(String period, Long deptId);

    /** 按期间 + 员工查 ACTIVE 实收明细（rd.employee_id 为空时按员工工号关联兜底）。 */
    List<PerformanceFactSummaryDTO> findActiveByEmployee(String period, Long employeeId);

    /**
     * 按期间 + 订单号/合同号查 ACTIVE 实收明细。
     * <p>orderNo 非空时与 contractNo 双键精确限定（同合同号挂多订单防串单）；
     * 为空时退化 contract_no/order_no 双键 OR 口径。
     */
    List<PerformanceFactSummaryDTO> findActiveByContract(String period, String orderNo, String contractNo);

    /**
     * 按业务键集合（订单号/合同号）查 ACTIVE 实收明细（<b>不限归属期间，跨月</b>）。
     * order_no 或 contract_no 命中键集合即返回。
     */
    List<PerformanceFactSummaryDTO> findActiveByBizKeys(Collection<String> bizKeys);

    /** 按实收明细 ID 集合查 ACTIVE 明细；不存在的 ID 不在结果中。 */
    List<PerformanceFactSummaryDTO> findActiveByIds(Collection<Long> ids);

    /** 按实收明细 ID 查单条（不限状态，溯源链路用；含 REVERSED）；不存在返回 null。 */
    PerformanceFactSummaryDTO getById(Long id);

    /**
     * 按期间查实收「合同」维度汇总（结佣申请列表合并展示用）。
     * 按 COALESCE(rc.order_no, rc.contract_no) 聚合；deptId 非空含下级部门。
     *
     * @param period 归属期间 YYYY-MM；可空，空时跨全部期间聚合（应收取该合同全部期间合计）
     */
    List<PerformanceContractSummaryDTO> listContractSummaries(String period, Long deptId, Long employeeId, String keyword);

    /**
     * 按期间 + 业务键集合（合同号/订单号）统计去重员工数（结佣明细列表跨合同合计用）。
     * 员工键口径与 {@link #listContractSummaries} 的 employeeCount 一致：
     * COALESCE(rd.employee_id, 工号兜底员工 e.employee_id, rd.employee_external_code)；
     * 仅统计 ACTIVE 明细，任一业务键（order_no 或 contract_no）命中即计入。
     *
     * @param period 归属期间 YYYY-MM；可空，空时统计全部期间
     * @return 去重员工数；无匹配返回 0
     */
    long countDistinctEmployeesByKeys(String period, Collection<String> bizKeys);

    /**
     * 按业务键集合（订单号/合同号）<b>跨期间</b>批量查 ACTIVE 实收明细的归属期间集合。
     * <p>用途：结佣发起页同期互斥排除——判断同一合同各实收期间是否均已有活跃结佣单。
     * 任一业务键（order_no 或 contract_no）命中即计入，调用方按输入键取值。
     *
     * @param bizKeys 订单号或合同号集合（不可为空）
     * @return bizKey → 该合同全部 ACTIVE 明细的期间集合；无 ACTIVE 明细的键不在结果中
     */
    Map<String, Set<String>> listActivePeriodsByKeys(Collection<String> bizKeys);

    /**
     * 批量查合同维度「调整前」实收金额合计。
     * <p>
     * 口径：以各业务键当前 ACTIVE rd 的 sourceKey 集合为准，沿明细链（同 source_key，
     * 含历史 REVERSED rd）取 id 最早一条金额求和；未调整时原值=当前合计。
     *
     * @return bizKey → 调整前合计；无 ACTIVE 明细的键不在结果中
     */
    Map<String, BigDecimal> sumOriginalAmountsByKeys(String period, Collection<String> bizKeys);

    /** 历史工资导入批次实收明细（ACTIVE，结佣 LOCKED 建单用；adjust_id 非空的调整新行不计入批次）。 */
    List<HistoryRealFactDTO> listRealFactsByBatch(String period, Long batchId);

    /**
     * 合同级实收金额调整：按订单下各 ACTIVE 明细当前金额占比分摊
     * （targetAmount − 当前合计）差额，逐条 supersede 为新金额。
     * <p>orderNo 非空时与 contractNo 双键精确限定（同合同号挂多订单防跨订单分摊）；
     * 尾差补到金额绝对值最大的一条，保证 Σ新金额 = targetAmount 精确成立。
     *
     * @return 旧明细 ID → 新明细 ID 映射（供结佣域回写 CommissionItem.performance_fact_id）
     */
    Map<Long, Long> adjustContractDetailsAmount(String period, String orderNo, String contractNo,
                                                 BigDecimal targetAmount, Long operatorId, Long adjustId);

    /** 明细级金额调整：单条实收明细 supersede 为 targetAmount。明细不存在返回 null。 */
    Long adjustDetailAmount(Long detailId, BigDecimal targetAmount, Long operatorId, Long adjustId);

    /** 明细级冲销（结佣调整 VOID）：单行置 REVERSED + reversal_type=MANUAL_ADJUST + adjust_id，不插新行。 */
    void voidDetail(Long detailId, Long operatorId, Long adjustId);

    /** 明细级部门划转：单条实收明细 supersede 为新部门（新行 rd.dept_id 覆盖合同级口径）。 */
    Long transferDetail(Long detailId, Long targetDeptId, Long operatorId, Long adjustId);

    /**
     * 实收自动对齐应收（§3.5）：同合同下 ACTIVE 实收明细按
     * （员工工号 + 角色）配对 PERF_EXPECT ACTIVE 应收，金额/分摊比例不一致（超容差）时
     * supersede 为应收口径，保留 receivedApplyId 关联；返回新旧明细映射供结佣域重绑。
     */
    ReceivedAlignmentResultDTO alignReceivedToExpected(String period, String contractNo, Long operatorId);
}
