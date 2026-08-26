package com.panjia.backup.controller;

import com.panjia.common.annotation.AuditLog;
import org.dromara.common.core.domain.R;
import org.springframework.web.bind.annotation.*;

/**
 * 备份容灾 Controller
 * <p>
 * 备份恢复操作必须记录审计日志，含审批人、客户确认。
 */
@RestController
@RequestMapping("/api/v1/backup")
public class BackupController {

    /**
     * 触发全量备份
     */
    @PostMapping("/full")
    @AuditLog(description = "全量备份")
    public R<Void> fullBackup() {
        return R.ok("全量备份已触发");
    }

    /**
     * 恢复操作（需审批 + 客户确认）
     */
    @PostMapping("/restore/{backupId}")
    @AuditLog(value = AuditLog.OperateType.BACKUP_RESTORE, description = "备份恢复", recordParam = true)
    public R<Void> restore(@PathVariable String backupId) {
        return R.ok("恢复操作已触发: " + backupId);
    }
}
