package com.panjia.performance.service;

/**
 * 期间封账服务。
 * <p>
 * 封账由算薪批次「总监锁定」自动触发（PayrollEventListener），无手工封账入口；
 * 解封（反结账）保留给结佣明细页的总监/财务纠错入口（§3.5 留痕审计）。
 * 封账后该期间的业绩事实不再允许新增、修改或冲销。
 */
public interface IPeriodCloseService {

    /**
     * 封账（系统内部调用：工资批次锁定自动封账）。
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
     * <p>反结账需强制录入原因，并保留原封账原因用于审计（§3.5 解封需总监二次确认 + AuditPort 留痕）。
     *
     * @param period     期间（YYYY-MM）
     * @param reason     反结账原因（必填，留痕审计）
     * @param operatorId 操作人 ID
     */
    void reopenPeriod(String period, String reason, Long operatorId);

    /**
     * 期间是否已封账。
     *
     * @param period 期间（YYYY-MM）
     * @return true 表示已封账
     */
    boolean isClosed(String period);
}
