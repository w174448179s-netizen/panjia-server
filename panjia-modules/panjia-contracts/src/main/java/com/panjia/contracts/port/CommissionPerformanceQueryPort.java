package com.panjia.contracts.port;

import com.panjia.contracts.dto.PerformanceContractSummaryDTO;
import com.panjia.contracts.dto.PerformanceFactSummaryDTO;
import com.panjia.contracts.dto.ReceivedAlignmentResultDTO;

import java.util.Collection;
import java.util.List;

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
    List<PerformanceContractSummaryDTO> listContractSummaries(String period, Long deptId, String factType);

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
}
