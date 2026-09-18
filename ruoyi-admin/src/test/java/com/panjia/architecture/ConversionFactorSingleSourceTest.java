package com.panjia.architecture;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 折算规则表「唯一读取者」守卫。
 * <p>
 * <b>为什么需要这个测试：</b>折算比例只有一个来源——薪酬域的
 * {@code pj_payroll_conversion_rule}，且全系统只允许经公共方法
 * {@code ConversionFactorPort}（实现 {@code ConversionFactorAdapter}）读取。
 * 一旦别的域在自己 SQL 里 JOIN 该表，就会形成「同一口径两处实现」：
 * 改规则只改一处、另一处悄悄不生效，且跨域直连表结构变更时报错点离改动点很远。
 * <p>
 * 该缺陷已发生过一次：{@code PerformanceFactMapper.selectContractSummaries}
 * 里内嵌了 {@code pj_payroll_conversion_rule} 子查询取 {@code conversionFactor}，
 * 业务侧另有一套 {@code factorsOf(…)} 逻辑，两边的生效区间/兜底口径各自演化。
 * <p>
 * <b>规则：</b>除属主模块 {@code panjia-payroll} 外，其它模块的 Java / SQL 源码中
 * <b>不得出现</b>该表名（javadoc / 注释里的说明性提及不算违规）。
 * <p>
 * <b>为什么放在 ruoyi-admin：</b>只有它的测试 classpath 能看到全部业务模块，
 * 业务模块内自测会漏掉兄弟模块。请勿移动本类。
 */
@Tag("dev")
class ConversionFactorSingleSourceTest {

    /** 折算规则表（属薪酬域） */
    private static final String RULE_TABLE = "pj_payroll_conversion_rule";

    /** 唯一允许持有该表的模块目录名 */
    private static final String OWNER_MODULE = "panjia-payroll";

    /** 待扫描的模块根：surefire 工作目录为 ruoyi-admin/，故向上到仓库根的 panjia-modules */
    private static final Path MODULES = Paths.get("..", "panjia-modules");

    @Test
    void onlyPayrollModuleMayReferenceConversionRuleTable() throws IOException {
        assertTrue(Files.isDirectory(MODULES),
            "未找到 panjia-modules 目录，路径基准可能已变：" + MODULES.toAbsolutePath());

        List<String> violations = new ArrayList<>();
        int scanned = 0;
        try (Stream<Path> stream = Files.walk(MODULES)) {
            List<Path> sources = stream
                .filter(Files::isRegularFile)
                .filter(p -> !p.toString().contains("target"))
                .filter(p -> {
                    String name = p.toString();
                    return name.endsWith(".java") || name.endsWith(".sql");
                })
                .filter(p -> !p.toString().contains("/" + OWNER_MODULE + "/"))
                .toList();
            for (Path file : sources) {
                scanned++;
                String[] lines = Files.readString(file, StandardCharsets.UTF_8).split("\n");
                for (int i = 0; i < lines.length; i++) {
                    if (isCommentLine(lines[i])) {
                        continue;
                    }
                    if (lines[i].contains(RULE_TABLE)) {
                        violations.add(file + ":" + (i + 1) + " → " + lines[i].trim());
                    }
                }
            }
        }
        assertTrue(scanned > 0, "未扫描到任何 Java/SQL 源码，路径基准可能已变");

        assertTrue(violations.isEmpty(),
            "折算规则表 " + RULE_TABLE + " 只允许薪酬域（" + OWNER_MODULE
                + "）持有，其它域请改用公共方法 ConversionFactorPort#factorOf / factorsOf 取比例"
                + "（不要在自己 SQL 里 JOIN 该表）：\n" + String.join("\n", violations));
    }

    /**
     * 是否为注释行（javadoc / 块注释续行 / 行注释 / SQL 行注释）。
     * <p>
     * 只跳过注释：SQL 语句体是普通代码行，不会被跳过。
     */
    private boolean isCommentLine(String line) {
        String trimmed = line.trim();
        return trimmed.startsWith("*") || trimmed.startsWith("/*")
            || trimmed.startsWith("//") || trimmed.startsWith("--");
    }
}
