package com.panjia.importdomain.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.panjia.importdomain.domain.ImportBatch;
import com.panjia.importdomain.domain.ImportIssue;
import com.panjia.importdomain.domain.ImportSourceType;
import com.panjia.importdomain.service.ImportBatchService;
import com.panjia.importdomain.template.ImportTemplateBridge;
import com.panjia.importutil.export.TemplateExporter;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.domain.R;
import org.dromara.common.satoken.utils.LoginHelper;
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
        try {
            Long batchId = importBatchService.importFromFile(type, file.getBytes(),
                file.getOriginalFilename(), period, LoginHelper.getUserId(), LoginHelper.getDeptId());
            return R.ok(batchId);
        } catch (Exception e) {
            log.error("导入失败", e);
            return R.fail("导入失败: " + e.getMessage());
        }
    }

    /**
     * 批次列表。
     */
    @SaCheckPermission("import:batch:list")
    @GetMapping("/batches")
    public R<List<ImportBatch>> list(
        @RequestParam(value = "sourceType", required = false) String sourceType,
        @RequestParam(value = "period", required = false) String period) {
        ImportSourceType type = sourceType == null ? null : ImportSourceType.fromCode(sourceType);
        return R.ok(importBatchService.list(type, period));
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
}
