package com.panjia.commission;

import com.panjia.contracts.dto.PerformanceFactSummaryDTO;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 结佣域静态契约校验（CI 规则落地，纯文件扫描 + 纯逻辑单测，无需 Spring 上下文）。
 * <p>
 * 覆盖：
 * <ul>
 *   <li>C6/C7：禁提成 / 折算字段命名（commission_amount / salary / conversion_rate / rate / point / ratio）；</li>
 *   <li>C9：禁止建 pj_commission_period_close 表（封账复用业绩域，走 Port）；</li>
 *   <li>C10：★ uk_citem_fact_active 必须排除 status &lt;&gt; 'REVERSED'；</li>
 *   <li>C16 锚点前提：增量重拉差集幂等（纯逻辑）+ 0 值过滤（纯逻辑）。</li>
 * </ul>
 */
@Tag("dev")
class CommissionStaticValidationTest {

    private static final Path MAIN_JAVA = Paths.get("src/main/java");
    private static final Path MAIN_SQL = Paths.get("src/main/resources/db/migration");

    /** C6/C7 禁用命名（词边界，避免 generate/separate 等误报） */
    private static final Pattern FORBIDDEN_NAMING = Pattern.compile(
        "(?i)(commission_amount|conversion_rate|salary|\\brate\\b|\\bpoint\\b|\\bratio\\b)");

    private static String read(Path path) throws IOException {
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    private static List<Path> allMainFiles(String suffix) throws IOException {
        List<Path> result = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(MAIN_JAVA)) {
            stream.filter(Files::isRegularFile)
                .filter(p -> p.toString().endsWith(suffix))
                .forEach(result::add);
        }
        try (Stream<Path> stream = Files.walk(MAIN_SQL)) {
            stream.filter(Files::isRegularFile)
                .filter(p -> p.toString().endsWith(".sql"))
                .forEach(result::add);
        }
        return result;
    }

    // ==================== CI C6/C7：禁提成/折算字段 ====================

    @Test
    void noCommissionSalaryOrConversionNamingInMainSources() throws IOException {
        List<Path> files = allMainFiles(".java");
        assertFalse(files.isEmpty(), "未扫描到主源码文件，路径配置错误");
        List<String> violations = new ArrayList<>();
        for (Path file : files) {
            String[] lines = read(file).split("\n");
            for (int i = 0; i < lines.length; i++) {
                if (FORBIDDEN_NAMING.matcher(lines[i]).find()) {
                    violations.add(file + ":" + (i + 1) + " → " + lines[i].trim());
                }
            }
        }
        assertTrue(violations.isEmpty(), "CI C6/C7 违规（禁提成/折算字段命名）：\n" + String.join("\n", violations));
    }

    // ==================== CI C9：禁建封账表 ====================

    @Test
    void noCommissionPeriodCloseTableInMigration() throws IOException {
        // 只拦真实建表语句；注释里的说明性提及（如"不建 pj_commission_period_close"）不算违规
        Pattern createCloseTable = Pattern.compile(
            "(?i)CREATE\\s+TABLE\\s+(IF\\s+NOT\\s+EXISTS\\s+)?pj_commission_period_close\\b");
        try (Stream<Path> stream = Files.walk(MAIN_SQL)) {
            stream.filter(Files::isRegularFile).forEach(sql -> {
                try {
                    String content = read(sql);
                    assertFalse(createCloseTable.matcher(content).find(),
                        "CI C9 违规：禁止建结佣封账表（封账复用业绩域，走 Port）：" + sql);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        }
    }

    // ==================== CI C10：uk_citem_fact_active 排除 REVERSED ====================

    @Test
    void ukCitemFactActiveMustExcludeReversed() throws IOException {
        Path init = MAIN_SQL.resolve("V150001__pj_commission_init.sql");
        assertTrue(Files.exists(init), "结佣初始化迁移脚本不存在：" + init);
        String content = read(init);
        assertTrue(content.contains("uk_citem_fact_active"), "缺少 uk_citem_fact_active 部分唯一索引");
        // ★ DISCOUNT 调整会「旧行 REVERSED + 新行沿用同一 performance_fact_id」，不排除 REVERSED 必撞键
        assertTrue(content.contains("status <> 'REVERSED'"),
            "CI C10 违规：uk_citem_fact_active 必须排除 status <> 'REVERSED'");
    }

    // ==================== 0 值过滤（ADR B14 / C-16 前提） ====================

    @Test
    void zeroAmountFactsMustBeFiltered() {
        List<PerformanceFactSummaryDTO> facts = List.of(
            fact(1L, new BigDecimal("100.00")),
            fact(2L, BigDecimal.ZERO),
            fact(3L, null),
            fact(4L, new BigDecimal("0.00")),
            fact(5L, new BigDecimal("-5.00")));

        List<PerformanceFactSummaryDTO> result =
            com.panjia.commission.service.CommissionApplicationService.filterNonZero(facts);

        // amount = 0 / null 不入单；负数（红冲类）保留由业务判断
        assertEquals(2, result.size());
        assertEquals(1L, result.get(0).getFactId());
        assertEquals(5L, result.get(1).getFactId());
    }

    // ==================== 增量重拉差集幂等（ADR B15 / C-17 前提） ====================

    @Test
    void incrementalRefreshDiffIsIdempotent() {
        List<PerformanceFactSummaryDTO> fetched = List.of(
            fact(1L, new BigDecimal("100.00")),
            fact(2L, new BigDecimal("200.00")),
            fact(3L, new BigDecimal("300.00")));

        // 第一次重拉：S = {1}，差集 = {2, 3}
        Set<Long> existing = new HashSet<>(Set.of(1L));
        List<PerformanceFactSummaryDTO> first =
            com.panjia.commission.service.CommissionApplicationService.computeIncrement(existing, fetched);
        assertEquals(2, first.size());

        // 追加后 S = {1, 2, 3}，重复重拉差集为空 → 零副作用
        existing.addAll(List.of(2L, 3L));
        List<PerformanceFactSummaryDTO> second =
            com.panjia.commission.service.CommissionApplicationService.computeIncrement(existing, fetched);
        assertTrue(second.isEmpty(), "重复重拉必须零副作用（幂等）");
    }

    private static PerformanceFactSummaryDTO fact(Long factId, BigDecimal amount) {
        PerformanceFactSummaryDTO dto = new PerformanceFactSummaryDTO();
        dto.setFactId(factId);
        dto.setAmount(amount);
        return dto;
    }
}
