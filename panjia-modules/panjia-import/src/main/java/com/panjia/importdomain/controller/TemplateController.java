package com.panjia.importdomain.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.panjia.importdomain.domain.ImportTemplate;
import com.panjia.importdomain.mapper.ImportTemplateMapper;
import com.panjia.importdomain.template.ImportTemplateBridge;
import com.panjia.importutil.export.TemplateExporter;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.domain.R;
import org.dromara.common.satoken.utils.LoginHelper;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 导入模板管理控制器。
 * <p>
 * 功能：模板列表、新增版本（复制已有模板）、激活切换、下载 Excel。
 * 同 source_type 仅 1 套激活模板，切换激活时先停用同 source_type 的旧激活模板。
 */
@Slf4j
@RequiredArgsConstructor
@RestController
@RequestMapping("/template")
public class TemplateController {

    private final ImportTemplateMapper templateMapper;
    private final ImportTemplateBridge templateBridge;

    /**
     * 模板列表（可按 sourceType 筛选）。
     */
    @SaCheckPermission("import:template:list")
    @GetMapping("/list")
    public R<List<ImportTemplate>> list(
            @RequestParam(value = "sourceType", required = false) String sourceType) {
        LambdaQueryWrapper<ImportTemplate> qw = new LambdaQueryWrapper<ImportTemplate>()
                .orderByDesc(ImportTemplate::getSourceType)
                .orderByDesc(ImportTemplate::getIsActive)
                .orderByDesc(ImportTemplate::getCreatedAt);
        if (sourceType != null && !sourceType.isBlank()) {
            qw.eq(ImportTemplate::getSourceType, sourceType);
        }
        List<ImportTemplate> templates = templateMapper.selectList(qw);
        return R.ok(templates);
    }

    /**
     * 模板详情。
     */
    @SaCheckPermission("import:template:list")
    @GetMapping("/{id}")
    public R<ImportTemplate> detail(@PathVariable Long id) {
        return R.ok(templateMapper.selectById(id));
    }

    /**
     * 新增模板版本。
     * <p>
     * 可选参数 copyFromId：复制已有模板的列映射和校验规则，调用方修改 version 后保存为新版本。
     * 新增的模板默认 is_active=false，需手动激活。
     */
    @SaCheckPermission("import:template:add")
    @PostMapping
    @Transactional(rollbackFor = Exception.class)
    public R<Long> add(@RequestBody ImportTemplate dto) {
        String operator = LoginHelper.getLoginUser().getUsername();
        dto.setId(null);
        dto.setIsActive(false);
        dto.setOptLockVersion(1);
        dto.setCreatedBy(operator);
        dto.setCreatedAt(LocalDateTime.now());
        dto.setUpdatedBy(operator);
        dto.setUpdatedAt(LocalDateTime.now());
        templateMapper.insert(dto);
        log.info("新增模板版本: code={}, version={}, operator={}",
                dto.getTemplateCode(), dto.getTemplateVersion(), operator);
        return R.ok(dto.getId());
    }

    /**
     * 复制已有模板为新版本。
     * <p>
     * 从 copyFromId 指定的模板复制全部字段，templateVersion 用请求参数的新版本号。
     */
    @SaCheckPermission("import:template:add")
    @PostMapping("/copy/{sourceId}")
    @Transactional(rollbackFor = Exception.class)
    public R<Long> copy(@PathVariable Long sourceId,
                        @RequestParam String newVersion,
                        @RequestParam(required = false) String newName) {
        ImportTemplate source = templateMapper.selectById(sourceId);
        if (source == null) {
            return R.fail("源模板不存在: id=" + sourceId);
        }
        String operator = LoginHelper.getLoginUser().getUsername();
        ImportTemplate copy = new ImportTemplate();
        copy.setTemplateCode(source.getTemplateCode());
        copy.setTemplateVersion(newVersion);
        copy.setOptLockVersion(1);
        copy.setTemplateName(newName != null && !newName.isBlank() ? newName : source.getTemplateName());
        copy.setSourceType(source.getSourceType());
        copy.setFileType(source.getFileType());
        copy.setSheetName(source.getSheetName());
        copy.setHeaderRow(source.getHeaderRow());
        copy.setDataStartRow(source.getDataStartRow());
        copy.setColumnMapping(source.getColumnMapping());
        copy.setValidationRules(source.getValidationRules());
        copy.setIsActive(false);
        copy.setDescription(source.getDescription());
        copy.setSourceFileVersion(source.getSourceFileVersion());
        copy.setRemark("复制自模板 id=" + sourceId);
        copy.setCreatedBy(operator);
        copy.setCreatedAt(LocalDateTime.now());
        copy.setUpdatedBy(operator);
        copy.setUpdatedAt(LocalDateTime.now());
        templateMapper.insert(copy);
        log.info("复制模板: sourceId={}, newVersion={}, newId={}, operator={}",
                sourceId, newVersion, copy.getId(), operator);
        return R.ok(copy.getId());
    }

    /**
     * 更新模板（列映射、校验规则、名称等）。
     */
    @SaCheckPermission("import:template:edit")
    @PutMapping
    @Transactional(rollbackFor = Exception.class)
    public R<Void> update(@RequestBody ImportTemplate dto) {
        if (dto.getId() == null) {
            return R.fail("模板 ID 不能为空");
        }
        String operator = LoginHelper.getLoginUser().getUsername();
        dto.setUpdatedBy(operator);
        dto.setUpdatedAt(LocalDateTime.now());
        templateMapper.updateById(dto);
        return R.ok();
    }

    /**
     * 激活模板。
     * <p>
     * 同 source_type 仅允许 1 套激活：先停用同 source_type 的所有模板，再激活指定模板。
     */
    @SaCheckPermission("import:template:activate")
    @PostMapping("/{id}/activate")
    @Transactional(rollbackFor = Exception.class)
    public R<Void> activate(@PathVariable Long id) {
        ImportTemplate template = templateMapper.selectById(id);
        if (template == null) {
            return R.fail("模板不存在: id=" + id);
        }
        String operator = LoginHelper.getLoginUser().getUsername();
        String sourceType = template.getSourceType();

        // 先停用同 source_type 的所有激活模板
        templateMapper.update(null,
                new LambdaUpdateWrapper<ImportTemplate>()
                        .eq(ImportTemplate::getSourceType, sourceType)
                        .eq(ImportTemplate::getIsActive, true)
                        .set(ImportTemplate::getIsActive, false)
                        .set(ImportTemplate::getUpdatedBy, operator)
                        .set(ImportTemplate::getUpdatedAt, LocalDateTime.now()));

        // 再激活指定模板
        templateMapper.update(null,
                new LambdaUpdateWrapper<ImportTemplate>()
                        .eq(ImportTemplate::getId, id)
                        .set(ImportTemplate::getIsActive, true)
                        .set(ImportTemplate::getUpdatedBy, operator)
                        .set(ImportTemplate::getUpdatedAt, LocalDateTime.now()));

        log.info("激活模板: id={}, sourceType={}, version={}, operator={}",
                id, sourceType, template.getTemplateVersion(), operator);
        return R.ok();
    }

    /**
     * 下载模板 Excel（表头 + 示例行）。
     */
    @SaCheckPermission("import:template:list")
    @GetMapping("/{id}/download")
    public void download(@PathVariable Long id, HttpServletResponse response) {
        ImportTemplate entity = templateMapper.selectById(id);
        if (entity == null) {
            try {
                response.sendError(404, "模板不存在");
            } catch (IOException ignored) {
            }
            return;
        }
        com.panjia.importutil.template.model.ImportTemplate tool = templateBridge.resolve(
                entity.getSourceType(), entity.getTemplateVersion());
        byte[] excel = TemplateExporter.toExcel(tool);
        String fileName = URLEncoder.encode(
                entity.getTemplateName() + ".xlsx", StandardCharsets.UTF_8);
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
}
