package com.panjia.importc.template;

import com.panjia.contracts.constant.NormalizedRecordFields;
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
 * 导入模板种子数据静态校验（CI P1，JUnit5）。
 * <p>
 * 对应任务卡 05 §四：对每个 pj_import_template 种子行，校验：
 * <ol>
 *   <li>column_mapping[*].source_column ∈ A-Z 字母</li>
 *   <li>column_mapping[*].target_field ∈ NormalizedRecordFields 白名单</li>
 *   <li>transform:lookup:{dict_type} 的 {dict_type} ∈ 全局字典白名单</li>
 *   <li>default_value 均为 JSON 字符串类型（或 null）</li>
 *   <li>source_header 非空</li>
 *   <li>sheet_name 不为空串 ''</li>
 * </ol>
 * 校验失败 → 测试不通过，禁止提交。
 * <p>
 * 数据来源：V100003__pj_import_template_seed.sql。
 */
@Tag("dev")
@DisplayName("导入模板种子静态校验")
class ImportTemplateStaticValidationTest {

    /** 字典类型白名单（01 卡矩阵定义） */
    private static final Set<String> DICT_WHITELIST = Set.of(
        "panjia_biz_type",
        "panjia_score_grade"
    );

    /** source_column 合法字母集合（A-Z） */
    private static final Pattern SOURCE_COLUMN_PATTERN = Pattern.compile("^[A-Z]+$");

    /** column_mapping JSONB 提取正则（单引号包围的 JSON 数组，DOTALL 跨行） */
    private static final Pattern COLUMN_MAPPING_PATTERN =
        Pattern.compile("'(\\[.*?])'\\s*::\\s*jsonb", Pattern.DOTALL);

    /** sheet_name 提取正则（VALUES 中 sheet_name 后的值） */
    private static final Pattern SHEET_NAME_PATTERN =
        Pattern.compile("sheet_name,.*?VALUES\\s*\\(", Pattern.DOTALL);

    private static final JsonMapper MAPPER = new JsonMapper();

    private static String seedSql;

    @BeforeAll
    static void loadSeed() throws IOException {
        Path sqlPath = Paths.get("src/main/resources/db/migration/V100003__pj_import_template_seed.sql");
        if (!Files.exists(sqlPath)) {
            // 兼容模块构建目录
            sqlPath = Paths.get("panjia-modules/panjia-import/src/main/resources/db/migration/V100003__pj_import_template_seed.sql");
        }
        assertTrue(Files.exists(sqlPath), "种子 SQL 文件必须存在: " + sqlPath);
        seedSql = Files.readString(sqlPath, StandardCharsets.UTF_8);
        assertFalse(seedSql.isBlank(), "种子 SQL 不能为空");
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
        // sheet_name 空串检查：SQL 不应出现 sheet_name 对应位置为 ''（空串）
        // sheet_name 可为 NULL（取第一个 sheet），但禁止空串 ''
        // 通过提取每条 INSERT 的 sheet_name 字段位置校验
        List<String> sheetNames = extractSheetNames();
        for (int i = 0; i < sheetNames.size(); i++) {
            String sheet = sheetNames.get(i);
            assertFalse("".equals(sheet.trim()),
                "sheet_name 禁止空串 ''（模板#" + i + "）");
        }
    }

    /**
     * 提取所有 column_mapping JSON 字符串。
     * <p>
     * 正则匹配 'JSON数组'::jsonb，只提取数组（column_mapping），不匹配对象（validation_rules）。
     *
     * @return column_mapping JSON 字符串列表
     */
    private List<String> extractColumnMappings() {
        List<String> result = new ArrayList<>();
        Matcher matcher = COLUMN_MAPPING_PATTERN.matcher(seedSql);
        while (matcher.find()) {
            result.add(matcher.group(1));
        }
        return result;
    }

    /**
     * 解析 column_mapping JSON 为列映射列表。
     *
     * @param json column_mapping JSON 字符串
     * @return 列映射列表
     * @throws Exception JSON 解析异常
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> parseColumnMapping(String json) throws Exception {
        return MAPPER.readValue(json, List.class);
    }

    /**
     * 提取所有 sheet_name 值。
     * <p>
     * 简化实现：按 INSERT 语句切分，定位 sheet_name 列位置，提取值。
     *
     * @return sheet_name 值列表
     */
    private List<String> extractSheetNames() {
        List<String> sheets = new ArrayList<>();
        // 匹配单引号字符串或 NULL
        Pattern valuePattern = Pattern.compile("'([^']*)'|NULL");
        Matcher insertMatcher = Pattern.compile("INSERT INTO pj_import_template.*?VALUES\\s*\\((.*?)\\);",
            Pattern.DOTALL).matcher(seedSql);
        while (insertMatcher.find()) {
            String valuesPart = insertMatcher.group(1);
            // sheet_name 是 VALUES 第 6 个字段（id,code,version,name,source,file_type,sheet_name,...）
            // 简化：直接搜索空串 '' 作为 sheet_name
            Matcher vm = valuePattern.matcher(valuesPart);
            List<String> vals = new ArrayList<>();
            while (vm.find()) {
                String v = vm.group(1) != null ? vm.group(1) : "NULL";
                vals.add(v);
            }
            // sheet_name 是第 7 个值（索引 6）
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
     * 校验 target_field ∈ NormalizedRecordFields 白名单。
     */
    private void validateTargetField(Map<String, Object> col, String context) {
        String targetField = (String) col.get("target_field");
        assertTrue(targetField != null && !targetField.isBlank(),
            context + " target_field 不能为空");
        assertTrue(NormalizedRecordFields.WHITELIST.contains(targetField),
            context + " target_field 不在白名单: " + targetField);
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
     * <p>
     * JSON 里 default_value 必须是字符串（如 "0"）或 null，不能是数字/布尔。
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
