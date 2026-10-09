package com.panjia.backup.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.panjia.backup.domain.vo.SysBackupFileVo;
import com.panjia.backup.service.BackupService;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.domain.R;
import org.dromara.common.log.annotation.Log;
import org.dromara.common.log.enums.BusinessType;
import org.dromara.common.redis.annotation.RepeatSubmit;
import org.dromara.common.web.core.BaseController;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 数据库备份恢复（系统管理 → 备份恢复）。
 * <p>
 * 备份/还原通过 Docker 容器内 pg_dump / pg_restore 执行（与 script/db 脚本同口径）；
 * 还原为整库覆盖的危险操作，前端需二次确认。URL 与权限标识保持 system:backup:*，
 * 菜单/按钮授权不受实现模块迁移影响。
 */
@Slf4j
@RequiredArgsConstructor
@RestController
@RequestMapping("/system/backup")
public class BackupController extends BaseController {

    private final BackupService backupService;

    /**
     * 备份文件列表（按备份时间倒序）。
     */
    @SaCheckPermission("system:backup:list")
    @GetMapping("/list")
    public R<List<SysBackupFileVo>> list() {
        return R.ok(backupService.listFiles());
    }

    /**
     * 立即备份（pg_dump 自定义格式，落服务器备份目录）。
     */
    @SaCheckPermission("system:backup:create")
    @RepeatSubmit(interval = 30000)
    @Log(title = "数据库备份", businessType = BusinessType.INSERT)
    @PostMapping("/create")
    public R<String> create() {
        return R.ok("备份完成", backupService.createBackup());
    }

    /**
     * 还原数据库（pg_restore --clean 整库覆盖，危险操作）。
     *
     * @param fileName 备份文件名
     */
    @SaCheckPermission("system:backup:restore")
    @Log(title = "数据库还原", businessType = BusinessType.UPDATE)
    @PostMapping("/restore/{fileName}")
    public R<Void> restore(@PathVariable String fileName) {
        backupService.restoreBackup(fileName);
        return R.ok("还原完成，建议刷新页面确认系统状态");
    }

    /**
     * 上传 dump 文件直接还原（先校验归档完整性再整库覆盖，危险操作）。
     * <p>multipart 大文件上传，前端 timeout 需放宽；复用还原权限。
     *
     * @param file pg_dump 自定义格式 .dump 备份文件
     */
    @SaCheckPermission("system:backup:restore")
    @Log(title = "数据库还原(上传文件)", businessType = BusinessType.UPDATE)
    @PostMapping("/restore-upload")
    public R<String> restoreUpload(@RequestParam("file") MultipartFile file) {
        String fileName = backupService.restoreFromUpload(file);
        return R.ok("文件 [" + fileName + "] 还原完成，建议刷新页面确认系统状态");
    }

    /**
     * 删除备份文件。
     *
     * @param fileName 备份文件名
     */
    @SaCheckPermission("system:backup:delete")
    @Log(title = "数据库备份删除", businessType = BusinessType.DELETE)
    @DeleteMapping("/{fileName}")
    public R<Void> delete(@PathVariable String fileName) {
        backupService.deleteBackup(fileName);
        return R.ok();
    }

    /**
     * 下载备份文件。
     *
     * @param fileName 备份文件名
     * @param response 响应对象
     */
    @SaCheckPermission("system:backup:download")
    @GetMapping("/download/{fileName}")
    public void download(@PathVariable String fileName, HttpServletResponse response) {
        Path file = backupService.resolveBackupFile(fileName);
        response.setContentType(MediaType.APPLICATION_OCTET_STREAM_VALUE);
        String encoded = URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20");
        response.setHeader("Content-Disposition", "attachment; filename*=UTF-8''" + encoded);
        response.setContentLengthLong(file.toFile().length());
        try (var in = Files.newInputStream(file); var out = response.getOutputStream()) {
            in.transferTo(out);
        } catch (Exception e) {
            log.error("[备份恢复] 下载失败：{}", fileName, e);
        }
    }
}
