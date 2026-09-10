package com.panjia.people.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;
import com.panjia.importutil.template.TemplateResolver;
import com.panjia.importutil.template.model.ColumnDef;
import com.panjia.people.domain.PeopleImportTemplate;
import com.panjia.people.mapper.PeopleImportTemplateMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

/**
 * 员工导入模板桥接器（V6.0）：把 pj_people_import_template 持久化模板
 * 转换为 common-import-util 工具层 {@link com.panjia.importutil.template.model.ImportTemplate} 内存模型。
 * <p>
 * 工具层只定义模板契约、不负责存储；本桥是 people 域对 common-import-util SPI 的实现。
 * column_json 直接以工具层 ColumnDef 模型存储（colName/field/type/required/enumValues/...）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PeopleImportTemplateBridge implements TemplateResolver {

    private final PeopleImportTemplateMapper templateMapper;

    private static final JsonMapper OBJECT_MAPPER = new JsonMapper();

    @Override
    public com.panjia.importutil.template.model.ImportTemplate resolve(String templateCode) {
        return resolve(templateCode, null);
    }

    @Override
    public com.panjia.importutil.template.model.ImportTemplate resolve(String templateCode, String version) {
        LambdaQueryWrapper<PeopleImportTemplate> qw = new LambdaQueryWrapper<PeopleImportTemplate>()
            .eq(PeopleImportTemplate::getTemplateCode, templateCode)
            .eq(PeopleImportTemplate::getEnabled, 1);
        if (version != null && !version.isBlank()) {
            qw.eq(PeopleImportTemplate::getTemplateVersion, version);
        }
        qw.orderByDesc(PeopleImportTemplate::getTemplateVersion).last("LIMIT 1");
        PeopleImportTemplate entity = templateMapper.selectOne(qw);
        if (entity == null) {
            throw new IllegalStateException("未找到启用的员工导入模板: code=" + templateCode
                + (version == null ? "" : ", version=" + version));
        }
        return toToolTemplate(entity);
    }

    private com.panjia.importutil.template.model.ImportTemplate toToolTemplate(PeopleImportTemplate entity) {
        com.panjia.importutil.template.model.ImportTemplate tool =
            new com.panjia.importutil.template.model.ImportTemplate();
        tool.setTemplateCode(entity.getTemplateCode());
        tool.setTemplateVersion(entity.getTemplateVersion());
        tool.setHeaderRow(0);
        tool.setColumns(parseColumns(entity.getColumnJson()));
        return tool;
    }

    private List<ColumnDef> parseColumns(String columnJson) {
        if (columnJson == null || columnJson.isBlank()) {
            return Collections.emptyList();
        }
        try {
            return OBJECT_MAPPER.readValue(columnJson, new TypeReference<List<ColumnDef>>() {});
        } catch (Exception e) {
            log.error("员工导入模板 column_json 解析失败", e);
            throw new IllegalStateException("员工导入模板列定义解析失败", e);
        }
    }
}
