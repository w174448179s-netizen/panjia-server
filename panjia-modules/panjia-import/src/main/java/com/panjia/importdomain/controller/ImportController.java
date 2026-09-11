package com.panjia.importdomain.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.panjia.importdomain.domain.ImportBatch;
import com.panjia.importdomain.domain.ImportIssue;
import com.panjia.importdomain.domain.ImportSourceType;
import com.panjia.importdomain.domain.raw.RawData;
import com.panjia.importdomain.service.ImportBatchService;
import com.panjia.importdomain.service.RawDataQueryService;
import com.panjia.importdomain.template.ImportTemplateBridge;
import com.panjia.importutil.export.TemplateExporter;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.domain.R;
import org.dromara.common.core.domain.PageResult;
import org.dromara.common.satoken.utils.LoginHelper;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 导入域控制器（V1.4）。
 */
@Slf4j
@RequiredArgsConstructor
@RestController
@RequestMapping("/import")
public class ImportController {

    private final ImportBatchService importBatchService;
    private final ImportTemplateBridge templateBridge;
    private final RawDataQueryService rawDataQueryService;

    /**
     * 文件上传导入。
     *
     * @param file       文件
     * @param sourceType 数据源类型 code
     * @param period     归属月 YYYY-MM
     */
    @SaCheckPermission("import:batch:upload")
    @PostMapping("/upload")
    public R<Long> upload(@RequestParam("file") MultipartFile file,
                          @RequestParam("sourceType") String sourceType,
                          @RequestParam(value = "period", required = false) String period) {
        ImportSourceType type = ImportSourceType.fromCode(sourceType);
        if (type == null) {
            return R.fail("未知数据源类型: " + sourceType);
        }
        // 归属月必填：批次/消费日志/业绩事实都按 period 组织，空 period 会导致
        // 事件 period=null 在下游 NOT NULL 列上炸掉
        if (period == null || period.isBlank()) {
            return R.fail("归属月不能为空，请选择导入归属月后再上传");
        }
        try {
            Long batchId = importBatchService.importFromFile(type, file.getBytes(),
                file.getOriginalFilename(), period, LoginHelper.getUserId(), LoginHelper.getDeptId());
            return R.ok(batchId);
        } catch (DataIntegrityViolationException e) {
            // 唯一索引冲突：通常出现在手工绕过 SUPERSEDED 流程的并发或脏数据场景。
            // 业务文案：避免把 PSQLException 整段塞给前端。
            log.warn("导入冲突 (sourceType={}, period={}): {}", sourceType, period, e.getMostSpecificCause().getMessage());
            return R.fail("该归属月已存在归档批次，本次导入未能完成。请刷新批次列表确认状态，或联系管理员处理");
        } catch (Exception e) {
            log.error("导入失败", e);
            return R.fail("导入失败: " + e.getMessage());
        }
    }

    /**
     * 批次列表。
     * <p>
     * 支持两种过滤方式：
     * <ul>
     *     <li>{@code sourceType}（兼容旧版）：单值精确匹配，e.g. {@code ATTENDANCE}</li>
     *     <li>{@code sourceTypes}（推荐）：多值 IN 过滤，e.g. {@code KE_SIGNED,KE_NEW_SIGN}，
     *         适合「贝壳业绩」菜单同时展示结佣+新签两类批次</li>
     * </ul>
     * 两个参数同时给出时，优先用 {@code sourceTypes}。
     */
    @SaCheckPermission("import:batch:list")
    @GetMapping("/batches")
    public R<List<ImportBatch>> list(
        @RequestParam(value = "sourceType", required = false) String sourceType,
        @RequestParam(value = "sourceTypes", required = false) java.util.List<String> sourceTypes,
        @RequestParam(value = "period", required = false) String period) {
        // 优先 sourceTypes 数组；空数组/单元素都视作未传
        java.util.List<ImportSourceType> types = null;
        if (sourceTypes != null && !sourceTypes.isEmpty()) {
            types = sourceTypes.stream()
                .map(ImportSourceType::fromCode)
                .filter(java.util.Objects::nonNull)
                .toList();
            if (types.isEmpty()) {
                types = null;
            }
        }
        if (types == null && sourceType != null && !sourceType.isBlank()) {
            types = java.util.List.of(ImportSourceType.fromCode(sourceType));
        }
        return R.ok(importBatchService.listBySourceTypes(types, period));
    }

    /**
     * 下载导入模板（根据 sourceType 从模板表生成 Excel，含表头 + 示例行）。
     *
     * @param sourceType 数据源类型 code
     * @param response   HTTP 响应
     */
    @SaCheckPermission("import:batch:upload")
    @GetMapping("/template/{sourceType}")
    public void downloadTemplate(@PathVariable String sourceType, HttpServletResponse response) {
        ImportSourceType type = ImportSourceType.fromCode(sourceType);
        if (type == null) {
            try {
                response.sendError(400, "未知数据源类型: " + sourceType);
            } catch (IOException ignored) {
            }
            return;
        }
        com.panjia.importutil.template.model.ImportTemplate template = templateBridge.resolve(sourceType);
        int colCount = template.getColumns() == null ? 0 : template.getColumns().size();
        log.info("导入模板: code={}, columns={}", sourceType, colCount);
        byte[] excel = TemplateExporter.toExcel(template);
        log.info("Excel 模板生成: {} bytes", excel.length);
        String fileName = URLEncoder.encode(type.getCode() + "_导入模板.xlsx", StandardCharsets.UTF_8);
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
     * 批次详情。
     */
    @SaCheckPermission("import:batch:list")
    @GetMapping("/batches/{id}")
    public R<ImportBatch> detail(@PathVariable Long id) {
        return R.ok(importBatchService.getById(id));
    }

    /**
     * 重归一化。
     */
    @SaCheckPermission("import:batch:renormalize")
    @PostMapping("/batches/{id}/renormalize")
    public R<Void> renormalize(@PathVariable Long id) {
        importBatchService.renormalize(id);
        return R.ok();
    }

    /**
     * 归档。
     */
    @SaCheckPermission("import:batch:archive")
    @PostMapping("/batches/{id}/archive")
    public R<Void> archive(@PathVariable Long id) {
        importBatchService.archive(id);
        return R.ok();
    }

    /**
     * 批次问题列表。
     */
    @SaCheckPermission("import:batch:list")
    @GetMapping("/batches/{id}/issues")
    public R<List<ImportIssue>> issues(@PathVariable Long id) {
        return R.ok(importBatchService.listIssues(id));
    }

    /**
     * 忽略问题。
     */
    @SaCheckPermission("import:issue:ignore")
    @PostMapping("/issues/{id}/ignore")
    public R<Void> ignoreIssue(@PathVariable Long id) {
        importBatchService.ignoreIssue(id);
        return R.ok();
    }

    /**
     * 批次原始数据列表（用于审计/追溯：按 row_no 倒序，分页）。
     */
    @SaCheckPermission("import:batch:list")
    @GetMapping("/batches/{id}/raw")
    public R<PageResult<RawData>> listRaw(@PathVariable Long id,
                                          @RequestParam(defaultValue = "1") Integer pageNum,
                                          @RequestParam(defaultValue = "50") Integer pageSize) {
        ImportBatch batch = importBatchService.getById(id);
        if (batch == null) {
            return R.fail("批次不存在: " + id);
        }
        // MyBatis-Plus 通过 EnumValue 把 source_type varchar 字段直接反序列化为
        // ImportSourceType 枚举，无需再 fromCode 转一次。
        return R.ok(rawDataQueryService.listRaw(batch.getSourceType(), id, pageNum, pageSize));
    }

    /**
     * 下载批次上传时的原文件（审计/追溯入口：用户下载后可直接用 Excel/Numbers/WPS 打开看）。
     * <p>
     * 归档时存的是 {@code storagePath}（local 相对路径或 minio 对象 key），由
     * {@link com.panjia.importutil.archive.FileArchiver#load(String)} 统一解释，
     * local 走磁盘、minio 走 S3 GetObject。
     */
    @SaCheckPermission("import:batch:list")
    @GetMapping("/batches/{id}/file")
    public void downloadOriginalFile(@PathVariable Long id, HttpServletResponse response) {
        byte[] content;
        String originalName;
        try {
            content = importBatchService.loadOriginalFile(id);
            originalName = importBatchService.getOriginalFileName(id);
        } catch (Exception e) {
            log.warn("下载原文件失败 batchId={}: {}", id, e.getMessage());
            try {
                response.sendError(404, e.getMessage());
            } catch (IOException ignored) {
            }
            return;
        }
        try {
            String encoded = URLEncoder.encode(originalName, StandardCharsets.UTF_8).replace("+", "%20");
            response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
            response.setHeader("Content-Disposition", "attachment; filename=\"" + encoded + "\"; filename*=UTF-8''" + encoded);
            response.setContentLength(content.length);
            try (OutputStream out = response.getOutputStream()) {
                out.write(content);
                out.flush();
            }
        } catch (IOException e) {
            log.warn("原文件下载写入失败 batchId={}: {}", id, e.getMessage());
        }
    }
}
