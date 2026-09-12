package com.panjia.contracts.port;

/**
 * 期间封账跨域查询端口（performance 域对外契约）。
 * <p>
 * 定义在 panjia-contracts 叶子模块，实现方为 panjia-performance（委托 pj_perf_period_close）。
 * 结佣域<b>不建封账表</b>（CI C9），一切以业绩域封账记录为准，经本端口只读实时查询。
 * <p>
 * 语义唯一（结佣域详细设计 §2.5）：
 * <ul>
 *   <li>{@code OPEN}：该结算月业绩数据未定稿 → 结佣窗口开启；</li>
 *   <li>{@code CLOSED}：该结算月终态关闭 → 结佣窗口关闭（拒绝发起 / 重拉 / 调整执行）。</li>
 * </ul>
 */
public interface PeriodCloseQueryPort {

    /**
     * 期间是否已封账。
     *
     * @param period 期间 YYYY-MM
     * @return true 表示已封账（CLOSED）；期间无记录视为未封账
     */
    boolean isClosed(String period);
}
