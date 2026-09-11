package com.panjia.performance.service;

import com.panjia.performance.dto.FactQuery;
import com.panjia.performance.dto.PerformanceFactDTO;
import org.dromara.common.core.domain.PageResult;
import org.dromara.common.mybatis.core.page.PageQuery;

import java.math.BigDecimal;
import java.util.List;

/**
 * 业绩查询服务。
 * <p>
 * 提供业绩事实的分页查询、详情查询、汇总统计、期间封账判断等只读能力。
 */
public interface PerformanceQueryService {

    /**
     * 分页查询业绩事实列表。
     * <p>
     * 支持按期间、事实口径、员工ID、部门ID、业务类型、状态、来源等条件筛选。
     *
     * @param query     筛选条件
     * @param pageQuery 分页参数
     * @return 业绩事实分页结果
     */
    PageResult<PerformanceFactDTO> listFacts(FactQuery query, PageQuery pageQuery);

    /**
     * 根据 ID 查询业绩事实详情。
     *
     * @param id 事实 ID
     * @return 业绩事实详情；不存在时返回 null
     */
    PerformanceFactDTO getFact(Long id);

    /**
     * 汇总业绩金额。
     * <p>
     * 参数均为可选，{@code employeeId}/{@code deptId} 可以为 null，
     * 为 null 时表示不限制该维度。
     *
     * @param period     归属期间（可选）
     * @param factType   事实口径（可选）
     * @param employeeId 员工 ID（可选）
     * @param deptId     部门 ID（可选）
     * @return 业绩金额汇总
     */
    BigDecimal sumPerformance(String period, String factType, Long employeeId, Long deptId);

    /**
     * 按员工 + 期间查询业绩明细。
     * <p>
     * 供 {@code CommissionPerformanceQueryPort} 等下游域调用。
     *
     * @param employeeId 员工 ID
     * @param period     归属期间
     * @param factType   事实口径
     * @return 业绩事实列表
     */
    List<PerformanceFactDTO> listByEmployeeAndPeriod(Long employeeId, String period, String factType);

    /**
     * 查询期间是否已封账。
     *
     * @param period 期间（YYYY-MM）
     * @return true 表示已封账
     */
    boolean isPeriodClosed(String period);
}
