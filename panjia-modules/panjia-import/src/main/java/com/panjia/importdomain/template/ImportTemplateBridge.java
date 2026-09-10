package com.panjia.importdomain.template;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;
import com.panjia.importdomain.domain.ImportTemplate;
import com.panjia.importdomain.mapper.ImportTemplateMapper;
import com.panjia.importutil.template.TemplateResolver;
import com.panjia.importutil.template.model.ColumnDef;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

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
        tool.setHeaderRow(entity.getHeaderRow() == null ? 0 : entity.getHeaderRow());
        tool.setColumns(toColumnDefs(entity.getColumnMapping()));
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
}
