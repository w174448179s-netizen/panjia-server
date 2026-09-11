package com.panjia.importdomain.template;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;
import com.panjia.importdomain.domain.ImportTemplate;
import com.panjia.importdomain.mapper.ImportTemplateMapper;
import com.panjia.importutil.template.TemplateResolver;
import com.panjia.importutil.template.model.ColumnDef;
import com.panjia.importutil.template.model.RuleDef;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 模板桥接器：把 pj_import_template 持久化模板转换为工具层 {@link ImportTemplate} 内存模型。
 * <p>
 * 工具层只定义模板契约、不负责存储；本桥是导入域对 common-import-util SPI 的实现。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ImportTemplateBridge implements TemplateResolver {

    private final ImportTemplateMapper templateMapper;
    private static final JsonMapper OBJECT_MAPPER = new JsonMapper();

    @Override
    public com.panjia.importutil.template.model.ImportTemplate resolve(String templateCode) {
        return resolve(templateCode, null);
    }

    @Override
    public com.panjia.importutil.template.model.ImportTemplate resolve(String templateCode, String version) {
        LambdaQueryWrapper<ImportTemplate> qw = new LambdaQueryWrapper<ImportTemplate>()
            .eq(ImportTemplate::getSourceType, templateCode)
            .eq(ImportTemplate::getIsActive, true);
        if (version != null && !version.isBlank()) {
            qw.eq(ImportTemplate::getTemplateVersion, version);
        }
        qw.last("LIMIT 1");
        ImportTemplate entity = templateMapper.selectOne(qw);
        if (entity == null) {
            throw new IllegalStateException("未找到激活模板: sourceType=" + templateCode
                + (version == null ? "" : ", version=" + version));
        }
        return toToolTemplate(entity);
    }

    /**
     * 取导入域模板实体（批次快照模板版本用）。
     */
    public ImportTemplate getActiveEntity(String sourceType) {
        ImportTemplate template = templateMapper.selectOne(
            new LambdaQueryWrapper<ImportTemplate>()
                .eq(ImportTemplate::getSourceType, sourceType)
                .eq(ImportTemplate::getIsActive, true)
                .last("LIMIT 1"));
        if (template == null) {
            throw new IllegalStateException("未找到激活模板: sourceType=" + sourceType);
        }
        return template;
    }

    private com.panjia.importutil.template.model.ImportTemplate toToolTemplate(ImportTemplate entity) {
        com.panjia.importutil.template.model.ImportTemplate tool =
            new com.panjia.importutil.template.model.ImportTemplate();
        tool.setTemplateCode(entity.getTemplateCode());
        tool.setTemplateVersion(entity.getTemplateVersion());
        // DB 层 header_row 为 1-based（约束 >= 1），工具层 XlsxFileParser 按 fesod 0-based rowIndex 匹配，
        // 此处统一转换为 0-based：header_row=1 → 首行为表头；header_row=2 → 两行表头取第 2 行。
        tool.setHeaderRow(entity.getHeaderRow() == null ? 0 : Math.max(0, entity.getHeaderRow() - 1));
        tool.setSheetName(entity.getSheetName());
        tool.setColumns(toColumnDefs(entity.getColumnMapping()));
        tool.setValidationRules(toRuleDefs(entity.getValidationRules()));
        return tool;
    }

    private List<ColumnDef> toColumnDefs(String columnMappingJson) {
        if (columnMappingJson == null || columnMappingJson.isBlank()) {
            return Collections.emptyList();
        }
        List<ColumnMapping> mappings;
        try {
            mappings = OBJECT_MAPPER.readValue(columnMappingJson,
                new TypeReference<List<ColumnMapping>>() {});
        } catch (Exception e) {
            log.error("模板 column_mapping 解析失败", e);
            throw new IllegalStateException("模板列映射解析失败", e);
        }
        List<ColumnDef> defs = new ArrayList<>();
        for (ColumnMapping m : mappings) {
            ColumnDef def = new ColumnDef();
            def.setColName(m.getSourceHeader());
            def.setField(m.getTargetField());
            def.setType(m.getType() == null ? "STRING" : m.getType().trim().toUpperCase());
            def.setRequired(Boolean.TRUE.equals(m.getRequired()));
            def.setDateFormat(extractDateFormat(m.getTransform()));
            defs.add(def);
        }
        return defs;
    }

    /** transform 形如 "date_format:yyyy-MM-dd" → 提取日期格式 */
    private String extractDateFormat(String transform) {
        if (transform == null) {
            return null;
        }
        String prefix = "date_format:";
        int idx = transform.indexOf(prefix);
        if (idx >= 0) {
            String rest = transform.substring(idx + prefix.length());
            int sep = rest.indexOf(',');
            return sep > 0 ? rest.substring(0, sep) : rest;
        }
        return null;
    }

    /**
     * 解析模板表 validation_rules JSONB 为结构化规则列表。
     * <p>
     * JSONB 结构：
     * <pre>
     * {
     *   "file_level": [...],
     *   "row_level": [
     *     {"field":"employeeExternalCode","rule":"not_blank","message":"..."},
     *     {"field":"receivedAmount","rule":"gte:0","message":"..."}
     *   ]
     * }
     * </pre>
     * <p>
     * 解析失败不抛异常，返回空列表 — 让校验阶段正常通过、issue 留给业务方查证。
     * 否则模板 seed 单条坏数据会让整个 import 启动失败。
     * <p>
     * 故意走 Map 反序列化而非强类型 — 避免引入 jackson-annotations 依赖，
     * 内嵌字段名只三个（field/rule/message），额外引入 POJO 性价比低。
     */
    private List<RuleDef> toRuleDefs(String validationRulesJson) {
        if (validationRulesJson == null || validationRulesJson.isBlank()) {
            return Collections.emptyList();
        }
        try {
            Map<String, Object> root = OBJECT_MAPPER.readValue(validationRulesJson,
                new TypeReference<Map<String, Object>>() {});
            if (root == null) {
                return Collections.emptyList();
            }
            Object rowLevel = root.get("row_level");
            if (!(rowLevel instanceof List)) {
                return Collections.emptyList();
            }
            List<RuleDef> defs = new ArrayList<>();
            for (Object raw : (List<?>) rowLevel) {
                if (!(raw instanceof Map)) {
                    continue;
                }
                Map<?, ?> m = (Map<?, ?>) raw;
                RuleDef def = new RuleDef();
                def.setField(asString(m.get("field")));
                def.setRule(asString(m.get("rule")));
                def.setMessage(asString(m.get("message")));
                // 跳过空 field 的脏数据
                if (def.getField() != null && !def.getField().isBlank()) {
                    defs.add(def);
                }
            }
            return defs;
        } catch (Exception e) {
            log.warn("模板 validation_rules 解析失败（退化按无规则校验）: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    private static String asString(Object o) {
        return o == null ? null : o.toString();
    }
}
