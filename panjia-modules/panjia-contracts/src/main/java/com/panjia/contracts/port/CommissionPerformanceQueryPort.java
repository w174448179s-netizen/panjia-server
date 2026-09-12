package com.panjia.contracts.port;

import com.panjia.contracts.dto.PerformanceFactSummaryDTO;

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
}
