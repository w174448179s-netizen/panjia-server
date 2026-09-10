package com.panjia.importc.template;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 导入模板种子数据静态校验（V2.0，JUnit5）。
 * <p>
 * 对每个 pj_import_template 种子行，校验：
 * <ol>
 *   <li>column_mapping[*].source_column ∈ A-Z 字母</li>
 *   <li>column_mapping[*].target_field ∈ RawData 实体字段白名单（五类单据 raw 列）</li>
 *   <li>transform:lookup:{dict_type} 的 {dict_type} ∈ 全局字典白名单</li>
 *   <li>default_value 均为 JSON 字符串类型（或 null）</li>
 *   <li>source_header 非空</li>
 *   <li>sheet_name 不为空串 ''</li>
 * </ol>
 * 校验失败 → 测试不通过，禁止提交。
 * <p>
 * V2.0：员工主数据模板已迁至 people 域（pj_people_import_template），
 * 本校验只覆盖 import 域五类交易单据模板。
 */
@Tag("dev")
@DisplayName("导入模板种子静态校验")
class ImportTemplateStaticValidationTest {

    /** 字典类型白名单 */
    private static final Set<String> DICT_WHITELIST = Set.of(
        "panjia_biz_type",
        "panjia_score_grade"
    );

    /**
     * target_field 白名单：五类交易单据 RawData 实体的标准化字段（camelCase）。
     * 与 DataSource impl 中 str/decimal/date/integer 读取的 field 一一对应。
     * 另含 V100003 旧版种子（is_active=false，已停用）的历史字段，保留至旧种子清理。
     */
    private static final Set<String> RAW_TARGET_FIELDS = Set.of(
        // KE_SIGNED / KE_NEW_SIGN
        "arriveMonth", "bizType", "orderNo", "contractNo",
        "roleSysNo", "roleName", "roleType", "shareRatio",
        "currentReceivable", "currentReceived",
        // ATTENDANCE
        "employeeCode", "attendDate", "lateCount", "absentDays",
        // POINTS
        "pointDate", "score", "violationCount",
        // OTHERS
        "itemType", "amount", "reason",
        // ===== V100003 旧版种子历史字段（停用模板兼容，勿用于新模板） =====
        "agentName", "performanceAmount", "signDate",
        "attendanceDays", "scoreValue", "grade"
    );

    /** source_column 合法字母集合（A-Z） */
    private static final Pattern SOURCE_COLUMN_PATTERN = Pattern.compile("^[A-Z]+$");

    /** column_mapping JSONB 提取正则（单引号包围的 JSON 数组，DOTALL 跨行） */
    private static final Pattern COLUMN_MAPPING_PATTERN =
        Pattern.compile("'(\\[.*?])'\\s*::\\s*jsonb", Pattern.DOTALL);

    private static final JsonMapper MAPPER = new JsonMapper();

    private static String seedSql;

    @BeforeAll
    static void loadSeed() throws IOException {
        // 扫描 db/migration 下所有模板种子 SQL（V*template*seed*.sql / V*template*v14.sql）
        Path dir = Paths.get("src/main/resources/db/migration");
        if (!Files.exists(dir)) {
            dir = Paths.get("panjia-modules/panjia-import/src/main/resources/db/migration");
        }
        assertTrue(Files.exists(dir), "迁移目录必须存在: " + dir);
        StringBuilder sb = new StringBuilder();
        try (var stream = Files.list(dir)) {
            stream.filter(p -> {
                String name = p.getFileName().toString();
                return name.endsWith(".sql")
                    && (name.contains("template") && (name.contains("seed") || name.contains("v14")));
            }).sorted().forEach(p -> {
                try {
                    sb.append(Files.readString(p, StandardCharsets.UTF_8)).append("\n");
                } catch (IOException e) {
                    throw new RuntimeException("读取失败: " + p, e);
                }
            });
        }
        seedSql = sb.toString();
        assertFalse(seedSql.isBlank(), "模板种子 SQL 不能为空");
    }

    @Test
    @DisplayName("校验所有 column_mapping 各项规约")
    void validateAllColumnMappings() throws Exception {
        List<String> columnMappings = extractColumnMappings();
        assertFalse(columnMappings.isEmpty(), "至少应有 1 条 column_mapping");

        for (int i = 0; i < columnMappings.size(); i++) {
            String json = columnMappings.get(i);
            List<Map<String, Object>> columns = parseColumnMapping(json);
            for (int j = 0; j < columns.size(); j++) {
                Map<String, Object> col = columns.get(j);
                String context = String.format("column_mapping[%d][%d]", i, j);
                validateSourceColumn(col, context);
                validateTargetField(col, context);
                validateLookupDictType(col, context);
                validateDefaultValue(col, context);
                validateSourceHeader(col, context);
            }
        }
    }

    @Test
    @DisplayName("校验 sheet_name 不为空串")
    void validateSheetNames() {
        List<String> sheetNames = extractSheetNames();
        for (int i = 0; i < sheetNames.size(); i++) {
            String sheet = sheetNames.get(i);
            assertFalse("".equals(sheet.trim()),
                "sheet_name 禁止空串 ''（模板#" + i + "）");
        }
    }

    /**
     * 提取所有 column_mapping JSON 字符串。
     */
    private List<String> extractColumnMappings() {
        List<String> result = new ArrayList<>();
        Pattern insertPattern = Pattern.compile(
            "INSERT INTO pj_import_template.*?VALUES\\s*\\((.*?)\\);", Pattern.DOTALL);
        Matcher insertMatcher = insertPattern.matcher(seedSql);
        while (insertMatcher.find()) {
            String valuesPart = insertMatcher.group(1);
            Matcher cmMatcher = COLUMN_MAPPING_PATTERN.matcher(valuesPart);
            while (cmMatcher.find()) {
                result.add(cmMatcher.group(1));
            }
        }
        return result;
    }

    /**
     * 解析 column_mapping JSON 为列映射列表。
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> parseColumnMapping(String json) throws Exception {
        return MAPPER.readValue(json, List.class);
    }

    /**
     * 提取所有 sheet_name 值（VALUES 第 7 个单引号字符串，索引 6）。
     */
    private List<String> extractSheetNames() {
        List<String> sheets = new ArrayList<>();
        Pattern valuePattern = Pattern.compile("'([^']*)'|NULL");
        Matcher insertMatcher = Pattern.compile("INSERT INTO pj_import_template.*?VALUES\\s*\\((.*?)\\);",
            Pattern.DOTALL).matcher(seedSql);
        while (insertMatcher.find()) {
            String valuesPart = insertMatcher.group(1);
            Matcher vm = valuePattern.matcher(valuesPart);
            List<String> vals = new ArrayList<>();
            while (vm.find()) {
                String v = vm.group(1) != null ? vm.group(1) : "NULL";
                vals.add(v);
            }
            if (vals.size() > 6) {
                sheets.add(vals.get(6));
            }
        }
        return sheets;
    }

    /**
     * 校验 source_column ∈ A-Z 字母。
     */
    private void validateSourceColumn(Map<String, Object> col, String context) {
        String sourceColumn = (String) col.get("source_column");
        assertTrue(sourceColumn != null && !sourceColumn.isBlank(),
            context + " source_column 不能为空");
        assertTrue(SOURCE_COLUMN_PATTERN.matcher(sourceColumn).matches(),
            context + " source_column 必须是 A-Z 字母，实际: " + sourceColumn);
    }

    /**
     * 校验 target_field ∈ RawData 实体字段白名单。
     */
    private void validateTargetField(Map<String, Object> col, String context) {
        String targetField = (String) col.get("target_field");
        assertTrue(targetField != null && !targetField.isBlank(),
            context + " target_field 不能为空");
        assertTrue(RAW_TARGET_FIELDS.contains(targetField),
            context + " target_field 不在 RawData 字段白名单: " + targetField);
    }

    /**
     * 校验 transform:lookup:{dict_type} 的 dict_type ∈ 白名单。
     */
    private void validateLookupDictType(Map<String, Object> col, String context) {
        String transform = (String) col.get("transform");
        if (transform != null && transform.startsWith("lookup:")) {
            String dictType = transform.substring("lookup:".length());
            assertFalse(dictType.isBlank(), context + " lookup dict_type 不能为空");
            assertTrue(DICT_WHITELIST.contains(dictType),
                context + " lookup dict_type 不在白名单: " + dictType);
        }
    }

    /**
     * 校验 default_value 为 JSON 字符串类型或 null。
     */
    private void validateDefaultValue(Map<String, Object> col, String context) {
        Object defaultValue = col.get("default_value");
        if (defaultValue == null) {
            return;
        }
        assertTrue(defaultValue instanceof String,
            context + " default_value 必须是字符串或 null，实际类型: " + defaultValue.getClass().getSimpleName());
    }

    /**
     * 校验 source_header 非空。
     */
    private void validateSourceHeader(Map<String, Object> col, String context) {
        String sourceHeader = (String) col.get("source_header");
        assertTrue(sourceHeader != null && !sourceHeader.isBlank(),
            context + " source_header 不能为空");
    }
}
