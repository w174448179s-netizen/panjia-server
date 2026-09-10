package com.panjia.people.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.panjia.importutil.export.TemplateExporter;
import com.panjia.importutil.template.model.ColumnDef;
import com.panjia.people.config.PeopleProperties;
import com.panjia.people.domain.PeopleImportTemplate;
import com.panjia.people.mapper.PeopleImportTemplateMapper;
import com.panjia.people.service.PeopleImportTemplateBridge;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.domain.R;
import org.dromara.common.satoken.utils.LoginHelper;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 员工导入模板管理控制器。
 * <p>
 * 功能：模板列表、新增版本、复制、激活切换、列映射编辑、下载 Excel。
 * 同 template_code 仅 1 套启用模板，切换启用时先停用旧版本。
 */
@Slf4j
@RequiredArgsConstructor
@RestController
@RequestMapping("/people/template")
public class PeopleTemplateController {

    private final PeopleImportTemplateMapper templateMapper;
    private final PeopleImportTemplateBridge templateBridge;
    private static final JsonMapper MAPPER = new JsonMapper();

    /**
     * 模板列表。
     */
    @SaCheckPermission("people:template:list")
    @GetMapping("/list")
    public R<List<PeopleImportTemplate>> list() {
        List<PeopleImportTemplate> list = templateMapper.selectList(
                new LambdaQueryWrapper<PeopleImportTemplate>()
                        .orderByDesc(PeopleImportTemplate::getEnabled)
                        .orderByDesc(PeopleImportTemplate::getTemplateVersion));
        return R.ok(list);
    }

    /**
     * 模板详情（含列定义 JSON）。
     */
    @SaCheckPermission("people:template:list")
    @GetMapping("/{id}")
    public R<PeopleImportTemplate> detail(@PathVariable Long id) {
        return R.ok(templateMapper.selectById(id));
    }

    /**
     * 获取列定义列表（解析 column_json 为 ColumnDef[]）。
     */
    @SaCheckPermission("people:template:list")
    @GetMapping("/{id}/columns")
    public R<List<ColumnDef>> getColumns(@PathVariable Long id) {
        PeopleImportTemplate entity = templateMapper.selectById(id);
        if (entity == null) {
            return R.fail("模板不存在: id=" + id);
        }
        try {
            List<ColumnDef> columns = MAPPER.readValue(entity.getColumnJson(),
                    new TypeReference<List<ColumnDef>>() {});
            return R.ok(columns);
        } catch (Exception e) {
            return R.fail("列定义解析失败: " + e.getMessage());
        }
    }

    /**
     * 保存列定义（整体替换 column_json）。
     */
    @SaCheckPermission("people:template:edit")
    @PutMapping("/{id}/columns")
    @Transactional(rollbackFor = Exception.class)
    public R<Void> saveColumns(@PathVariable Long id, @RequestBody List<ColumnDef> columns) {
        if (columns == null || columns.isEmpty()) {
            return R.fail("列定义不能为空");
        }
        PeopleImportTemplate entity = templateMapper.selectById(id);
        if (entity == null) {
            return R.fail("模板不存在: id=" + id);
        }
        if (entity.getEnabled() != null && entity.getEnabled() == 1) {
            return R.fail("已启用的模板不允许直接编辑，请先停用或复制为新版本");
        }
        try {
            String json = MAPPER.writeValueAsString(columns);
            entity.setColumnJson(json);
            templateMapper.updateById(entity);
            log.info("保存员工导入模板列定义: id={}, columns={}, operator={}",
                    id, columns.size(), LoginHelper.getLoginUser().getUsername());
            return R.ok();
        } catch (Exception e) {
            return R.fail("保存失败: " + e.getMessage());
        }
    }

    /**
     * 新增模板版本。
     */
    @SaCheckPermission("people:template:add")
    @PostMapping
    @Transactional(rollbackFor = Exception.class)
    public R<Long> add(@RequestBody PeopleImportTemplate dto) {
        dto.setId(null);
        dto.setEnabled(0);
        if (dto.getCreateTime() == null) {
            dto.setCreateTime(LocalDateTime.now());
        }
        templateMapper.insert(dto);
        log.info("新增员工导入模板: code={}, version={}, operator={}",
                dto.getTemplateCode(), dto.getTemplateVersion(),
                LoginHelper.getLoginUser().getUsername());
        return R.ok(dto.getId());
    }

    /**
     * 复制为新版本。
     */
    @SaCheckPermission("people:template:add")
    @PostMapping("/copy/{sourceId}")
    @Transactional(rollbackFor = Exception.class)
    public R<Long> copy(@PathVariable Long sourceId,
                        @RequestParam String newVersion,
                        @RequestParam(required = false) String newName) {
        PeopleImportTemplate source = templateMapper.selectById(sourceId);
        if (source == null) {
            return R.fail("源模板不存在: id=" + sourceId);
        }
        PeopleImportTemplate copy = new PeopleImportTemplate();
        copy.setTemplateCode(source.getTemplateCode());
        copy.setTemplateVersion(newVersion);
        copy.setColumnJson(source.getColumnJson());
        copy.setEnabled(0);
        copy.setCreateTime(LocalDateTime.now());
        templateMapper.insert(copy);
        log.info("复制员工导入模板: sourceId={}, newVersion={}, newId={}, operator={}",
                sourceId, newVersion, copy.getId(),
                LoginHelper.getLoginUser().getUsername());
        return R.ok(copy.getId());
    }

    /**
     * 启用模板（同 template_code 仅 1 套启用）。
     */
    @SaCheckPermission("people:template:activate")
    @PostMapping("/{id}/activate")
    @Transactional(rollbackFor = Exception.class)
    public R<Void> activate(@PathVariable Long id) {
        PeopleImportTemplate template = templateMapper.selectById(id);
        if (template == null) {
            return R.fail("模板不存在: id=" + id);
        }
        String operator = LoginHelper.getLoginUser().getUsername();
        String code = template.getTemplateCode();

        // 先停用同 template_code 的所有启用模板
        templateMapper.update(null,
                new LambdaUpdateWrapper<PeopleImportTemplate>()
                        .eq(PeopleImportTemplate::getTemplateCode, code)
                        .eq(PeopleImportTemplate::getEnabled, 1)
                        .set(PeopleImportTemplate::getEnabled, 0));

        // 再启用指定模板
        templateMapper.update(null,
                new LambdaUpdateWrapper<PeopleImportTemplate>()
                        .eq(PeopleImportTemplate::getId, id)
                        .set(PeopleImportTemplate::getEnabled, 1));

        log.info("启用员工导入模板: id={}, code={}, version={}, operator={}",
                id, code, template.getTemplateVersion(), operator);
        return R.ok();
    }

    /**
     * 下载模板 Excel。
     */
    @SaCheckPermission("people:template:list")
    @GetMapping("/{id}/download")
    public void download(@PathVariable Long id, HttpServletResponse response) {
        PeopleImportTemplate entity = templateMapper.selectById(id);
        if (entity == null) {
            try {
                response.sendError(404, "模板不存在");
            } catch (IOException ignored) {
            }
            return;
        }
        com.panjia.importutil.template.model.ImportTemplate tool =
                templateBridge.resolve(entity.getTemplateCode(), entity.getTemplateVersion());
        byte[] excel = TemplateExporter.toExcel(tool);
        String fileName = URLEncoder.encode(
                "员工导入模板_" + entity.getTemplateVersion() + ".xlsx",
                StandardCharsets.UTF_8);
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setHeader("Content-Disposition", "attachment; filename=\"" + fileName + "\"");
        response.setHeader("Cache-Control", "no-cache, no-store, must-revalidate");
        response.setHeader("Pragma", "no-cache");
        response.setHeader("Expires", "0");
        response.setContentLength(excel.length);
        try (OutputStream out = response.getOutputStream()) {
            out.write(excel);
            out.flush();
        } catch (IOException e) {
            log.warn("模板下载写入失败", e);
        }
    }

    /**
     * 版本对比（返回两个模板的列定义差异）。
     */
    @SaCheckPermission("people:template:list")
    @GetMapping("/compare")
    public R<List<TemplateColumnDiff>> compare(@RequestParam Long sourceId,
                                               @RequestParam Long targetId) {
        PeopleImportTemplate source = templateMapper.selectById(sourceId);
        PeopleImportTemplate target = templateMapper.selectById(targetId);
        if (source == null || target == null) {
            return R.fail("模板不存在");
        }
        try {
            List<ColumnDef> sourceCols = MAPPER.readValue(source.getColumnJson(),
                    new TypeReference<List<ColumnDef>>() {});
            List<ColumnDef> targetCols = MAPPER.readValue(target.getColumnJson(),
                    new TypeReference<List<ColumnDef>>() {});
            return R.ok(ColumnDefDiffUtils.compare(sourceCols, targetCols));
        } catch (Exception e) {
            return R.fail("对比失败: " + e.getMessage());
        }
    }

    /**
     * 列差异条目。
     */
    @lombok.Data
    public static class TemplateColumnDiff {
        private String field;
        private String changeType; // ADDED / REMOVED / MODIFIED / UNCHANGED
        private String sourceColName;
        private String targetColName;
        private String sourceType;
        private String targetType;
        private Boolean sourceRequired;
        private Boolean targetRequired;
        private String diffDetail;
    }

    /**
     * 列定义对比工具类。
     */
    static class ColumnDefDiffUtils {
        static List<TemplateColumnDiff> compare(List<ColumnDef> source, List<ColumnDef> target) {
            List<TemplateColumnDiff> result = new ArrayList<>();
            java.util.Map<String, ColumnDef> sourceMap = new java.util.LinkedHashMap<>();
            java.util.Map<String, ColumnDef> targetMap = new java.util.LinkedHashMap<>();
            for (ColumnDef c : source) sourceMap.put(c.getField(), c);
            for (ColumnDef c : target) targetMap.put(c.getField(), c);

            // 遍历 target，找新增和修改
            for (ColumnDef tCol : target) {
                TemplateColumnDiff diff = new TemplateColumnDiff();
                diff.setField(tCol.getField());
                diff.setTargetColName(tCol.getColName());
                diff.setTargetType(tCol.getType());
                diff.setTargetRequired(tCol.isRequired());
                ColumnDef sCol = sourceMap.get(tCol.getField());
                if (sCol == null) {
                    diff.setChangeType("ADDED");
                    diff.setDiffDetail("新增列");
                } else {
                    StringBuilder detail = new StringBuilder();
                    if (!equals(sCol.getColName(), tCol.getColName())) {
                        detail.append("表头: ").append(sCol.getColName()).append(" → ").append(tCol.getColName()).append("; ");
                    }
                    if (!equals(sCol.getType(), tCol.getType())) {
                        detail.append("类型: ").append(sCol.getType()).append(" → ").append(tCol.getType()).append("; ");
                    }
                    if (sCol.isRequired() != tCol.isRequired()) {
                        detail.append("必填: ").append(sCol.isRequired()).append(" → ").append(tCol.isRequired()).append("; ");
                    }
                    diff.setSourceColName(sCol.getColName());
                    diff.setSourceType(sCol.getType());
                    diff.setSourceRequired(sCol.isRequired());
                    if (detail.length() > 0) {
                        diff.setChangeType("MODIFIED");
                        diff.setDiffDetail(detail.toString());
                    } else {
                        diff.setChangeType("UNCHANGED");
                        diff.setDiffDetail("无变化");
                    }
                }
                result.add(diff);
            }
            // 遍历 source，找删除的
            for (ColumnDef sCol : source) {
                if (!targetMap.containsKey(sCol.getField())) {
                    TemplateColumnDiff diff = new TemplateColumnDiff();
                    diff.setField(sCol.getField());
                    diff.setSourceColName(sCol.getColName());
                    diff.setSourceType(sCol.getType());
                    diff.setSourceRequired(sCol.isRequired());
                    diff.setChangeType("REMOVED");
                    diff.setDiffDetail("删除列");
                    result.add(diff);
                }
            }
            return result;
        }

        private static boolean equals(String a, String b) {
            return java.util.Objects.equals(a, b);
        }
    }
}
