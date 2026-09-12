package com.panjia.performance;

import com.panjia.contracts.dto.NormalizedRecordDTO;
import com.panjia.performance.domain.FactType;
import com.panjia.performance.service.PerformanceEngine;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 业绩双口径（PERF_REAL / PERF_EXPECT）契约校验（V4.2 算薪事实对齐 / C-12 锚点）。
 * <p>
 * 纯逻辑单测 + 迁移脚本静态扫描，无需 Spring 上下文。覆盖：
 * <ul>
 *   <li>SIGNED 行必须双发 REAL+EXPECT，其余类型不产事实（含已废弃的 NEW_SIGN）；</li>
 *   <li>金额按口径取数：REAL=实收 receivedAmount，EXPECT=应收 receivableAmount，缺列回退 originAmount；</li>
 *   <li>部分唯一索引 uk_perf_fact_source_key 必须以 fact_type 打头（双发同 sourceKey 共存前提）。</li>
 * </ul>
 */
@Tag("dev")
class PerformanceDualFactTest {

    private static final Path MAIN_SQL = Paths.get("src/main/resources/db/migration");

    // ==================== 事实口径分发契约 ====================

    @Test
    void signedRecordEmitsRealAndExpectInOrder() {
        NormalizedRecordDTO signed = new NormalizedRecordDTO();
        signed.setRecordType("SIGNED");

        List<FactType> types = PerformanceEngine.factTypesForRecord(signed);

        assertEquals(2, types.size(), "SIGNED 行必须双发两条事实");
        assertEquals(FactType.PERF_REAL, types.get(0), "REAL 必须在前（事件发布顺序稳定）");
        assertEquals(FactType.PERF_EXPECT, types.get(1));
    }

    @Test
    void nonPerformanceRecordsEmitNothing() {
        // NEW_SIGN 为已废弃的第二导入来源类型（现唯一来源 KE_SIGNED 双口径），历史脏值也不得产事实
        for (String type : List.of("NEW_SIGN", "ATTENDANCE", "POINTS", "MANUAL")) {
            NormalizedRecordDTO dto = new NormalizedRecordDTO();
            dto.setRecordType(type);
            assertTrue(PerformanceEngine.factTypesForRecord(dto).isEmpty(),
                type + " 不得生成业绩事实");
        }
        assertTrue(PerformanceEngine.factTypesForRecord(null).isEmpty());
        assertTrue(PerformanceEngine.factTypesForRecord(new NormalizedRecordDTO()).isEmpty());
    }

    // ==================== 金额口径契约（C-12：REAL 192,556.89 / EXPECT 206,274.04） ====================

    @Test
    void realTakesReceivedAndExpectTakesReceivable() {
        // 经纪人业绩明细行：应收 206,274.04 口径与实收 192,556.89 口径同携于一条 SIGNED 行
        NormalizedRecordDTO signed = new NormalizedRecordDTO();
        signed.setRecordType("SIGNED");
        signed.setReceivableAmount(new BigDecimal("206274.04"));
        signed.setReceivedAmount(new BigDecimal("192556.89"));
        signed.setOriginAmount(new BigDecimal("192556.89"));

        // 当前金额（贝壳折算后原值）：REAL=实收、EXPECT=应收
        assertEquals(new BigDecimal("192556.89"),
            PerformanceEngine.resolveFactCurrentAmount(signed, FactType.PERF_REAL),
            "PERF_REAL 当前金额必须取实收");
        assertEquals(new BigDecimal("206274.04"),
            PerformanceEngine.resolveFactCurrentAmount(signed, FactType.PERF_EXPECT),
            "PERF_EXPECT 当前金额必须取应收");
    }

    @Test
    void originAmountIsCurrentAmountDividedByBrokerRate() {
        // 原始金额 = 贝壳当前金额 ÷ 经纪人折算比例（默认 85%），四舍五入保留 2 位
        assertEquals(new BigDecimal("117.65"),
            PerformanceEngine.grossUpOriginAmount(new BigDecimal("100.00"), new BigDecimal("0.85")),
            "100 ÷ 0.85 必须为 117.65");
        assertEquals(new BigDecimal("243851.81"),
            PerformanceEngine.grossUpOriginAmount(new BigDecimal("207274.04"), new BigDecimal("0.85")),
            "原始金额按比例还原后保留 2 位小数");
        // null 金额透传 null（交由折算引擎按 0 处理），比例为 null/0 时按默认 85% 兜底
        assertEquals(null,
            PerformanceEngine.grossUpOriginAmount(null, new BigDecimal("0.85")));
        assertEquals(new BigDecimal("117.65"),
            PerformanceEngine.grossUpOriginAmount(new BigDecimal("100.00"), null),
            "比例为 null 时按默认 0.85 兜底");
        assertEquals(new BigDecimal("117.65"),
            PerformanceEngine.grossUpOriginAmount(new BigDecimal("100.00"), BigDecimal.ZERO),
            "比例为 0 时按默认 0.85 兜底，不得除零");
    }

    @Test
    void missingDedicatedAmountFallsBackToOrigin() {
        // 历史单口径行缺实收/应收列时回退 originAmount，不得 NPE / 不得串口径
        NormalizedRecordDTO legacy = new NormalizedRecordDTO();
        legacy.setOriginAmount(new BigDecimal("300.00"));

        assertEquals(new BigDecimal("300.00"),
            PerformanceEngine.resolveFactCurrentAmount(legacy, FactType.PERF_REAL));
        assertEquals(new BigDecimal("300.00"),
            PerformanceEngine.resolveFactCurrentAmount(legacy, FactType.PERF_EXPECT));

        assertEquals(null, PerformanceEngine.resolveFactCurrentAmount(null, FactType.PERF_REAL));
        NormalizedRecordDTO empty = new NormalizedRecordDTO();
        assertEquals(null, PerformanceEngine.resolveFactCurrentAmount(empty, FactType.PERF_REAL));
    }

    // ==================== 双发共存前提：部分唯一索引含 fact_type ====================

    @Test
    void uniqueIndexMustStartWithFactType() throws IOException {
        Path init = MAIN_SQL.resolve("V140002__pj_perf_init.sql");
        assertTrue(Files.exists(init), "业绩初始化迁移脚本不存在：" + init);
        String content = Files.readString(init, StandardCharsets.UTF_8);
        assertTrue(content.contains("uk_perf_fact_source_key"), "缺少 uk_perf_fact_source_key 部分唯一索引");
        // 索引列必须 fact_type 打头，否则同一 sourceKey 双发 REAL+EXPECT 会互相撞键
        assertTrue(content.contains("ON pj_perf_fact(fact_type, source_key, fact_status)"),
            "uk_perf_fact_source_key 必须 (fact_type, source_key, fact_status) 顺序，保证双口径共存");
        assertTrue(content.contains("WHERE fact_status = 'ACTIVE'"),
            "唯一索引必须为 ACTIVE 部分索引（REVERSED 历史行不占键）");
    }

    @Test
    void migrationDirectoryReadable() throws IOException {
        // 防御：确认扫描目录真实存在且有迁移脚本（上面的静态断言不是空跑）
        try (Stream<Path> stream = Files.walk(MAIN_SQL)) {
            long sqlCount = stream.filter(Files::isRegularFile)
                .filter(p -> p.toString().endsWith(".sql"))
                .count();
            assertTrue(sqlCount >= 1, "业绩域迁移脚本目录为空，路径配置错误");
        }
    }
}
