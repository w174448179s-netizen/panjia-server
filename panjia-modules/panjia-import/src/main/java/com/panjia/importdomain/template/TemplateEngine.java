package com.panjia.importdomain.template;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.panjia.importdomain.domain.ImportSourceType;
import com.panjia.importdomain.domain.ImportTemplate;
import com.panjia.importdomain.mapper.ImportTemplateMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 模板引擎（V1.4 §3.7）。
 * <p>
 * 按 source_type 查激活模板，解析 column_mapping JSONB，驱动列映射。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TemplateEngine {

    private final ImportTemplateMapper templateMapper;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /**
     * 获取指定 source_type 的激活模板。
     *
     * @param sourceType 数据源类型
     * @return 激活模板；不存在抛异常
     */
    public ImportTemplate getActiveTemplate(ImportSourceType sourceType) {
        ImportTemplate template = templateMapper.selectOne(
            new LambdaQueryWrapper<ImportTemplate>()
                .eq(ImportTemplate::getSourceType, sourceType.getCode())
                .eq(ImportTemplate::getIsActive, true)
                .last("LIMIT 1")
        );
        if (template == null) {
            throw new IllegalStateException("未找到激活模板: sourceType=" + sourceType.getCode());
        }
        return template;
    }

    /**
     * 解析模板的 column_mapping 为列映射列表。
     *
     * @param template 模板
     * @return 列映射列表
     */
    public List<ColumnMapping> parseColumnMapping(ImportTemplate template) {
        if (template.getColumnMapping() == null || template.getColumnMapping().isBlank()) {
            return Collections.emptyList();
        }
        try {
            return OBJECT_MAPPER.readValue(template.getColumnMapping(),
                new TypeReference<List<ColumnMapping>>() {});
        } catch (Exception e) {
            log.error("模板 column_mapping 解析失败: templateId={}", template.getId(), e);
            throw new IllegalStateException("模板列映射解析失败", e);
        }
    }

    /**
     * 按列映射把 Excel 原始行（header→value）转成标准化行（field→value）。
     *
     * @param mappings  列映射列表
     * @param excelRows Excel 原始行列表
     * @return 标准化行列表
     */
    public List<Map<String, Object>> mapColumns(List<ColumnMapping> mappings,
                                                List<Map<String, Object>> excelRows) {
        // 构建 source_header → target_field 索引
        java.util.Map<String, ColumnMapping> headerIndex = new java.util.HashMap<>();
        for (ColumnMapping m : mappings) {
            if (m.getSourceHeader() != null) {
                headerIndex.put(m.getSourceHeader(), m);
            }
        }
        return excelRows.stream().map(row -> {
            Map<String, Object> standardized = new java.util.LinkedHashMap<>();
            for (Map.Entry<String, Object> e : row.entrySet()) {
                ColumnMapping cm = headerIndex.get(e.getKey());
                if (cm != null) {
                    standardized.put(cm.getTargetField(), e.getValue());
                }
            }
            return standardized;
        }).toList();
    }
}
