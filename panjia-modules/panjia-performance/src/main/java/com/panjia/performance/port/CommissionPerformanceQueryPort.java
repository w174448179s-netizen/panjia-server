package com.panjia.performance.port;

import com.panjia.performance.dto.PerformanceFactDTO;

import java.math.BigDecimal;
import java.util.List;

/**
 * 对外（佣金域）只读查询端口。
 * <p>
 * 佣金域等下游模块通过此端口查询业绩汇总与明细数据，以及期间封账状态。
 * 实现位于 performance 域 service 层。
 */
public interface CommissionPerformanceQueryPort {

    /**
     * 按员工+期间汇总业绩。
     *
     * @param employeeId 员工ID
     * @param period     归属期间
     * @param factType   事实口径
     * @return 业绩金额汇总
     */
    BigDecimal sumPerformanceByEmployeeAndPeriod(Long employeeId, String period, String factType);

    /**
     * 按员工+期间查询业绩明细。
     *
     * @param employeeId 员工ID
     * @param period     归属期间
     * @param factType   事实口径
     * @return 业绩事实明细列表
     */
    List<PerformanceFactDTO> listByEmployeeAndPeriod(Long employeeId, String period, String factType);

    /**
     * 查询期间是否已封账。
     *
     * @param period 归属期间
     * @return true-已封账，false-未封账
     */
    boolean isPeriodClosed(String period);
}
