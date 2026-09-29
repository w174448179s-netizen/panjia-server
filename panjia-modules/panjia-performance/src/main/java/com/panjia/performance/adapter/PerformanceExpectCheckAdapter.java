package com.panjia.performance.adapter;

import com.panjia.contracts.port.PerformanceExpectCheckPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * PerformanceExpectCheckPort 实现（业绩域）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PerformanceExpectCheckAdapter implements PerformanceExpectCheckPort {

    private final JdbcTemplate jdbcTemplate;

    @Override
    public boolean hasAnyExpectFacts() {
        Integer cnt = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM pj_perf_fact WHERE fact_type = 'PERF_EXPECT'",
            Integer.class);
        boolean has = cnt != null && cnt > 0;
        log.info("[PERF_EXPECT 检查] fact_type=PERF_EXPECT count={}, has={}", cnt, has);
        return has;
    }
}
