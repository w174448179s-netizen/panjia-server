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

    /**
     * 按模板 code 取激活模板（模板下载等需要精确指定模板的场景）。
     */
    public ImportTemplate getActiveByCode(String templateCode) {
        ImportTemplate template = templateMapper.selectOne(
            new LambdaQueryWrapper<ImportTemplate>()
                .eq(ImportTemplate::getTemplateCode, templateCode)
                .eq(ImportTemplate::getIsActive, true)
                .last("LIMIT 1"));
        if (template == null) {
            throw new IllegalStateException("未找到激活模板: templateCode=" + templateCode);
        }
        return template;
    }

    /**
     * 取首选激活模板：同 source_type 多套激活时，返回映射列数最多者
     * （原始文件模板信息量最大，作为"下载模板"的缺省对象）。
     */
    public ImportTemplate getPreferredActive(String sourceType) {
        List<ImportTemplate> actives = templateMapper.selectList(
            new LambdaQueryWrapper<ImportTemplate>()
                .eq(ImportTemplate::getSourceType, sourceType)
                .eq(ImportTemplate::getIsActive, true));
        if (actives.isEmpty()) {
            throw new IllegalStateException("未找到激活模板: sourceType=" + sourceType);
        }
        ImportTemplate best = actives.get(0);
        int bestCols = parseMappings(best.getColumnMapping()).size();
        for (ImportTemplate t : actives) {
            int cols = parseMappings(t.getColumnMapping()).size();
            if (cols > bestCols) {
                best = t;
                bestCols = cols;
            }
        }
        return best;
    }

    /**
     * 按文件表头自动匹配激活模板（多模板共存时）。
     * <p>
     * 匹配规则：候选模板的所有映射列（source_header，trim 后）都出现在文件表头行
     * （按模板 header_row 取行）中才算合格；合格者取映射列数最多者（原始文件列多，
     * 优先于其列子集的简版模板）。仅一套激活模板时直接返回，不做匹配。
     *
     * @param sourceType 数据源类型（=pj_import_template.source_type）
     * @param headerRows 嗅探到的文件行文本（1-based：get(0) 即文件第 1 行）
     * @return 匹配到的工具层模板
     */
    public com.panjia.importutil.template.model.ImportTemplate resolveByHeaders(
            String sourceType, List<List<String>> headerRows) {
        List<ImportTemplate> actives = templateMapper.selectList(
            new LambdaQueryWrapper<ImportTemplate>()
                .eq(ImportTemplate::getSourceType, sourceType)
                .eq(ImportTemplate::getIsActive, true)
                .orderByDesc(ImportTemplate::getUpdatedAt));
        if (actives.isEmpty()) {
            throw new IllegalStateException("未找到激活模板: sourceType=" + sourceType);
        }
        if (actives.size() == 1) {
            return toToolTemplate(actives.get(0));
        }

        ImportTemplate best = null;
        int bestScore = -1;
        List<String> missReasons = new ArrayList<>();
        for (ImportTemplate t : actives) {
            List<ColumnMapping> mappings = parseMappings(t.getColumnMapping());
            if (mappings.isEmpty()) {
                continue;
            }
            // 多行表头合并（与 XlsxFileParser.invokeHeadMap 一致）：收集
            // [headerRow, dataStartRow) 范围内的所有表头行，对同一列取最后一个非空值
            // （子表头覆盖主表头）。单行表头（dataStartRow 未配或 = headerRow+1）
            // 时只取 headerRow 一行，行为不变。
            // 钉钉月度汇总：headerRow=3 / dataStartRow=5，第 3 行 H-I 合并为「请假」、
            // 第 4 行子表头分别为「事假(天)」「病假(天)」，合并后 H=事假(天)、I=病假(天)，
            // 即可命中模板列映射；若仅取 headerRow 单行，H/I 匹配失败导致 2/16 缺失，
            // 与简版 ATTENDANCE_SIMPLE 共存后触发「无模板匹配」回归。
            int headerRowIdx = t.getHeaderRow() == null ? 1 : t.getHeaderRow();
            int dataStartRowIdx = t.getDataStartRow() == null
                ? headerRowIdx + 1
                : Math.max(t.getDataStartRow(), headerRowIdx + 1);
            List<String> mergedHeaders = new ArrayList<>();
            for (int r = headerRowIdx; r < dataStartRowIdx; r++) {
                int idx = r - 1;
                if (idx < 0 || idx >= headerRows.size()) {
                    continue;
                }
                List<String> fileRow = headerRows.get(idx);
                if (fileRow == null) {
                    continue;
                }
                while (mergedHeaders.size() < fileRow.size()) {
                    mergedHeaders.add(null);
                }
                for (int i = 0; i < fileRow.size(); i++) {
                    String v = fileRow.get(i);
                    if (v != null && !v.isBlank()) {
                        mergedHeaders.set(i, v.trim());
                    }
                }
            }
            if (mergedHeaders.isEmpty()) {
                missReasons.add(t.getTemplateCode() + ": 文件无第 " + headerRowIdx + " 行表头");
                continue;
            }
            java.util.Set<String> fileHeaders = new java.util.HashSet<>();
            for (String h : mergedHeaders) {
                if (h != null && !h.isBlank()) {
                    fileHeaders.add(h.trim());
                }
            }
            int matched = 0;
            for (ColumnMapping m : mappings) {
                if (m.getSourceHeader() != null
                    && fileHeaders.contains(com.panjia.importutil.template.HeaderNames
                        .normalize(m.getSourceHeader()))) {
                    matched++;
                }
            }
            if (matched == mappings.size() && mappings.size() > bestScore) {
                best = t;
                bestScore = mappings.size();
            } else if (matched < mappings.size()) {
                missReasons.add(t.getTemplateCode() + ": 缺少列（"
                    + (mappings.size() - matched) + "/" + mappings.size() + "）");
            }
        }
        if (best == null) {
            throw new IllegalStateException(
                "文件表头与任何激活模板都不匹配，请使用「下载导入模板」获取正确格式。"
                    + String.join("；", missReasons));
        }
        log.info("多模板表头匹配: sourceType={}, 选中={}/{}", sourceType,
            best.getTemplateCode(), best.getTemplateVersion());
        return toToolTemplate(best);
    }

    private List<ColumnMapping> parseMappings(String columnMappingJson) {
        if (columnMappingJson == null || columnMappingJson.isBlank()) {
            return Collections.emptyList();
        }
        try {
            return OBJECT_MAPPER.readValue(columnMappingJson,
                new TypeReference<List<ColumnMapping>>() {});
        } catch (Exception e) {
            log.error("模板 column_mapping 解析失败", e);
            return Collections.emptyList();
        }
    }

    private com.panjia.importutil.template.model.ImportTemplate toToolTemplate(ImportTemplate entity) {
        com.panjia.importutil.template.model.ImportTemplate tool =
            new com.panjia.importutil.template.model.ImportTemplate();
        tool.setTemplateCode(entity.getTemplateCode());
        tool.setTemplateVersion(entity.getTemplateVersion());
        // DB 层 header_row 为 1-based（约束 >= 1），工具层 XlsxFileParser 按 fesod 0-based rowIndex 匹配，
        // 此处统一转换为 0-based：header_row=1 → 首行为表头；header_row=2 → 两行表头取第 2 行。
        tool.setHeaderRow(entity.getHeaderRow() == null ? 0 : Math.max(0, entity.getHeaderRow() - 1));
        // data_start_row 同为 1-based；仅当数据并非紧邻表头下一行（多行表头）时显式下发，
        // 为空表示沿用工具层默认（headerRow + 1）。
        if (entity.getDataStartRow() != null) {
            tool.setDataStartRow(Math.max(0, entity.getDataStartRow() - 1));
        }
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
            def.setTransform(m.getTransform());
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
