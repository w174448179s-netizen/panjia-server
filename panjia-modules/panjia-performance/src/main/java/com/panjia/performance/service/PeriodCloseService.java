package com.panjia.performance.service;

import com.panjia.performance.domain.PerformancePeriodClose;

import java.util.List;

/**
 * 期间封账服务。
 * <p>
 * 管理业绩期间的封账/反结账操作，封账后该期间的业绩事实不再允许新增、修改或冲销。
 */
public interface PeriodCloseService {

    /**
     * 查询所有期间（按期间倒序）。
     *
     * @return 期间封账记录列表
     */
    List<PerformancePeriodClose> listPeriods();

    /**
     * 查询单个期间。
     *
     * @param period 期间（YYYY-MM）
     * @return 期间封账记录；不存在时返回 null
     */
    PerformancePeriodClose getPeriod(String period);

    /**
     * 封账。
     * <p>
     * 状态流转：OPEN → CLOSED。
     * 如果期间记录不存在则先创建（OPEN 状态），然后改为 CLOSED。
     *
     * @param period     期间（YYYY-MM）
     * @param reason     封账原因
     * @param operatorId 操作人 ID
     */
    void closePeriod(String period, String reason, Long operatorId);

    /**
     * 反结账（重新开启）。
     * <p>
     * 状态流转：CLOSED → OPEN。
     *
     * @param period     期间（YYYY-MM）
     * @param operatorId 操作人 ID
     */
    void reopenPeriod(String period, Long operatorId);

    /**
     * 期间是否已封账。
     *
     * @param period 期间（YYYY-MM）
     * @return true 表示已封账
     */
    boolean isClosed(String period);
}
