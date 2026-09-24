package com.panjia.contracts.port;

import com.panjia.contracts.dto.HistoryRealFactDTO;
import com.panjia.contracts.dto.PerformanceContractSummaryDTO;
import com.panjia.contracts.dto.PerformanceFactSummaryDTO;
import com.panjia.contracts.dto.ReceivedAlignmentResultDTO;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 业绩事实跨域查询端口（performance 域对外契约，对结佣域唯一出口）。
 * <p>
 * 定义在 panjia-contracts 叶子模块，实现方为 panjia-performance，
 * 结佣域（panjia-commission）只依赖本端口，<b>严禁直连 {@code pj_perf_*} 表</b>（CI C3/C4）。
 * <p>
 * 口径约定：
 * <ul>
 *   <li>所有查询只返回事实快照字段，金额为业绩域原样值，消费方不得二次折算；</li>
 *   <li>{@code findActiveByDept} / {@code findActiveByEmployee} / {@code findActiveByFacts}
 *       只返回 {@code ACTIVE} 状态事实（含 amount = 0 的行，0 值过滤由结佣域自行处理）；</li>
 *   <li>{@code getByFactId} 供溯源链路使用，任意状态事实均可查（含 REVERSED）。</li>
 * </ul>
 */
public interface CommissionPerformanceQueryPort {

    /**
     * 按期间 + 门店查 ACTIVE 业绩事实。
     *
     * @param period   归属期间 YYYY-MM
     * @param deptId   门店 ID
     * @param factType 事实口径（FactType code：PERF_REAL / PERF_EXPECT）
     * @return 事实摘要列表（含 amount = 0 的行，过滤留给消费方）
     */
    List<PerformanceFactSummaryDTO> findActiveByDept(String period, Long deptId, String factType);

    /**
     * 按期间 + 员工查 ACTIVE 业绩事实。
     *
     * @param period     归属期间 YYYY-MM
     * @param employeeId 员工 ID
     * @param factType   事实口径（FactType code）
     * @return 事实摘要列表
     */
    List<PerformanceFactSummaryDTO> findActiveByEmployee(String period, Long employeeId, String factType);

    /**
     * 按事实 ID 集合查 ACTIVE 业绩事实（冲销联动 / 批量溯源用）。
     *
     * @param factIds 事实 ID 集合
     * @return 事实摘要列表（仅 ACTIVE；不存在的 ID 不在结果中）
     */
    List<PerformanceFactSummaryDTO> findActiveByFacts(Collection<Long> factIds);

    /**
     * 按事实 ID 查单条事实（不限状态，溯源链路用；含 REVERSED）。
     *
     * @param factId 事实 ID
     * @return 事实摘要；不存在返回 null
     */
    PerformanceFactSummaryDTO getByFactId(Long factId);

    /**
     * 按期间 + 合同号查 ACTIVE 业绩事实（结佣按合同发起用）。
     *
     * @param period     归属期间 YYYY-MM
     * @param contractNo 合同号
     * @param factType   事实口径（FactType code：PERF_REAL / PERF_EXPECT）
     * @return 事实摘要列表（含合同号/订单号/房源地址；含 amount = 0 的行，过滤留给消费方）
     */
    List<PerformanceFactSummaryDTO> findActiveByContract(String period, String contractNo, String factType);

    /**
     * 按期间查「合同」维度业绩汇总（结佣申请列表与合同申请单合并展示用）。
     *
     * @param period   归属期间 YYYY-MM
     * @param deptId   门店 ID（null 查全部；非 null 含下级部门，与业绩明细页口径一致）
     * @param factType 事实口径（FactType code）
     * @return 合同维度摘要列表（仅 contract_no 非空的合同，按签约时间倒序由调用方排序）
     */
    List<PerformanceContractSummaryDTO> listContractSummaries(String period, Long deptId, String factType, Long employeeId);

    /**
     * 批量查合同维度「调整前」事实金额合计（结佣明细列表展示「原值 → 调整后值」用）。
     * <p>
     * 口径：以各合同当前 ACTIVE 事实的 sourceKey 集合为准，沿事实链（同 sourceKey，
     * 含历史 REVERSED 事实）取最早一条事实金额求和；从未调整的合同其原值=当前合计。
     * 已 VOID 冲销（无 ACTIVE 事实）的行不计入原值，与当前列表口径一致。
     *
     * @param period   归属期间 YYYY-MM
     * @param bizKeys  合同号/订单号业务键集合（不可为空）
     * @param factType 事实口径（FactType code：PERF_REAL / PERF_EXPECT）
     * @return bizKey → 调整前合计；无 ACTIVE 事实的键不在结果中
     */
    Map<String, BigDecimal> sumOriginalAmountsByKeys(String period, Collection<String> bizKeys, String factType);

    /**
     * 实收自动对齐应收（§3.5，结佣总监审批发现差异时调用）。
     * <p>
     * 将指定合同当月每条 ACTIVE 的 PERF_REAL 事实，按同 sourceKey 的 PERF_EXPECT 事实口径
     * （原始金额/折算系数/分摊比例/业绩金额）supersede 为新事实，实现「合同总额 + 每人明细」
     * 实收全部对齐应收；已一致的事实保持不变。对齐后返回新旧事实映射供结佣域重绑明细。
     *
     * @param period     归属期间 YYYY-MM
     * @param contractNo 合同号
     * @param operatorId 操作人 ID（系统办理记总监审批人）
     * @return 对齐结果（含替换映射与对齐前后合计）；无对应应收事实的实收事实保持不变
     */
    ReceivedAlignmentResultDTO alignReceivedToExpected(String period, String contractNo, Long operatorId);

    /**
     * 合同级金额调整：按合同下指定口径各 ACTIVE 事实当前金额占比分摊
     * （targetAmount − 当前合计）差额，逐条 supersede 为新金额（结佣调整用）。
     * <p>
     * 分摊尾差补到业绩金额绝对值最大的一条，保证 Σ新金额 = targetAmount 精确成立。
     * 与 {@code PerformanceAdjustServiceImpl.executeContractAmountAdjust} 同口径。
     *
     * @param period       归属期间
     * @param contractNo   合同号
     * @param factType     事实口径（PERF_REAL / PERF_EXPECT）
     * @param targetAmount 调整后合计
     * @param operatorId   操作人 ID
     * @param adjustId     调整单 ID（写入新事实 adjust_id 与冲销链）
     * @return 旧事实 ID → 新事实 ID 映射（供结佣域回写 CommissionItem.performance_fact_id）
     */
    Map<Long, Long> adjustContractFactsAmount(String period, String contractNo, String factType,
                                              BigDecimal targetAmount, Long operatorId, Long adjustId);

    /**
     * 明细级金额调整：单条事实 supersede 为 targetAmount（结佣调整用）。
     *
     * @param factId       事实 ID
     * @param targetAmount 调整后金额
     * @param operatorId   操作人 ID
     * @param adjustId     调整单 ID
     * @return 新事实 ID
     */
    Long adjustFactAmount(Long factId, BigDecimal targetAmount, Long operatorId, Long adjustId);

    /**
     * 明细级业绩冲销：单条事实冲销（结佣调整 VOID 用）。
     *
     * @param factId     事实 ID
     * @param operatorId 操作人 ID
     * @param adjustId   调整单 ID
     */
    void voidFact(Long factId, Long operatorId, Long adjustId);

    /**
     * 明细级部门划转：单条事实 supersede 为新部门（结佣调整 TRANSFER 用）。
     *
     * @param factId       事实 ID
     * @param targetDeptId 目标部门 ID
     * @param operatorId   操作人 ID
     * @param adjustId     调整单 ID
     * @return 新事实 ID
     */
    Long transferFact(Long factId, Long targetDeptId, Long operatorId, Long adjustId);

    /**
     * 历史工资导入批次实收事实明细（历史 LOCKED 结佣建单用）。
     * <p>
     * 返回指定批次下 factType=PERF_REAL 且 ACTIVE 的事实行（含订单号/合同号/
     * 房源地址/费用项等建单字段）。是否已绑定结佣明细的过滤由结佣域自行处理
     * （查自身 pj_commission_item.performance_fact_id）。
     *
     * @param period  归属期间 YYYY-MM
     * @param batchId 导入批次 ID
     * @return 实收事实明细（按事实 ID 升序）；无数据返回空列表
     */
    List<HistoryRealFactDTO> listRealFactsByBatch(String period, Long batchId);

    /**
     * 按业务键汇总期间应收事实金额（历史 LOCKED 建单 expected_amount 用）。
     * <p>
     * 口径：factType=PERF_EXPECT 且 ACTIVE，业务键 = 订单号优先，空回退合同号，
     * 再回退 sourceKey（与事实生成侧 buildSourceKeyPrefix 的业务键约定一致）。
     *
     * @param period  归属期间 YYYY-MM
     * @param bizKeys 业务键集合（不可为空）
     * @return bizKey → 应收合计；无事实的键不在结果中
     */
    Map<String, BigDecimal> sumExpectAmountsByKeys(String period, Collection<String> bizKeys);
}
