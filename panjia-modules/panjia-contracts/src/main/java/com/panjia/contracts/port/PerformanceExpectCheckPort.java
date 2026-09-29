package com.panjia.contracts.port;

/**
 * 业绩事实存在性检查端口（import 域跨域调用，performance 域实现）。
 * <p>
 * 实收导入前置校验：无新签应收事实（PERF_EXPECT）时拒绝实收导入，
 * 避免"先实收后新签"导致 expected_amount=0、分流误判全部 APPROVED。
 */
public interface PerformanceExpectCheckPort {

    /**
     * 是否存在任何 PERF_EXPECT（应收/新签）事实。
     * <p>不限制 period（跨月场景：新签 8 月、实收 9 月，PERF_EXPECT 存在即可）。
     *
     * @return true=有，允许实收导入；false=无，拒绝实收导入
     */
    boolean hasAnyExpectFacts();
}
