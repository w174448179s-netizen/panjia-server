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
     * <p>
     * TODO: V2 规划中，待实现全量备份逻辑（含快照、增量同步、校验等）。
     */
    @PostMapping("/full")
    @AuditLog(description = "全量备份")
    public R<Void> fullBackup() {
        return R.fail("备份功能暂未实现，V2 规划中");
    }

    /**
     * 恢复操作（需审批 + 客户确认）
     * <p>
     * TODO: V2 规划中，待实现恢复逻辑（含备份校验、审批流程、客户确认等）。
     */
    @PostMapping("/restore/{backupId}")
    @AuditLog(value = AuditLog.OperateType.BACKUP_RESTORE, description = "备份恢复", recordParam = true)
    public R<Void> restore(@PathVariable String backupId) {
        return R.fail("备份功能暂未实现，V2 规划中");
    }
}
