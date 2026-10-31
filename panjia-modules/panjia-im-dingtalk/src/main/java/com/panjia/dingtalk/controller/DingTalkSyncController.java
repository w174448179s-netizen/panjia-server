package com.panjia.dingtalk.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.annotation.SaMode;
import com.panjia.dingtalk.config.DingTalkConfigProvider;
import com.panjia.dingtalk.domain.DingTalkSyncLog;
import com.panjia.dingtalk.service.DingTalkAttendanceSyncService;
import com.panjia.dingtalk.service.DingTalkScoreSyncService;
import com.panjia.dingtalk.service.DingTalkSyncLogService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.domain.PageResult;
import org.dromara.common.core.domain.R;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.log.annotation.Log;
import org.dromara.common.log.enums.BusinessType;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.common.web.core.BaseController;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 钉钉同步入口（基础档案 → 钉钉同步）。
 */
@Slf4j
@RequiredArgsConstructor
@RestController
@RequestMapping("/dingtalk/sync")
public class DingTalkSyncController extends BaseController {

    private final DingTalkAttendanceSyncService attendanceSyncService;
    private final DingTalkScoreSyncService scoreSyncService;
    private final DingTalkSyncLogService syncLogService;
    private final DingTalkConfigProvider configProvider;

    /**
     * 手动同步考勤（月度汇总 + 每日明细，异步执行）。
     *
     * @param period 考勤期间 YYYY-MM
     */
    @SaCheckPermission("dingtalk:sync:attendance")
    @Log(title = "钉钉考勤同步", businessType = BusinessType.OTHER)
    @PostMapping("/attendance")
    public R<DingTalkSyncLog> syncAttendance(@RequestParam String period) {
        assertEnabled();
        return R.ok("已开始同步，完成后可在同步记录中查看结果",
            attendanceSyncService.startAsync(period, currentUserId()));
    }

    /**
     * 手动同步积分（钉钉积分日报日志，异步执行）。
     *
     * @param period 积分期间 YYYY-MM
     */
    @SaCheckPermission("dingtalk:sync:score")
    @Log(title = "钉钉积分同步", businessType = BusinessType.OTHER)
    @PostMapping("/score")
    public R<DingTalkSyncLog> syncScore(@RequestParam String period) {
        assertEnabled();
        return R.ok("已开始同步，完成后可在同步记录中查看结果",
            scoreSyncService.startAsync(period, currentUserId()));
    }

    /**
     * 同步记录分页（按开始时间倒序，含执行中/成功/部分成功/失败）。
     *
     * @param syncType 同步类型：ATTENDANCE(考勤) / SCORE(积分)，默认考勤
     */
    @SaCheckPermission(value = {"dingtalk:sync:attendance", "dingtalk:sync:score"}, mode = SaMode.OR)
    @GetMapping("/logs")
    public R<PageResult<DingTalkSyncLog>> logs(
        @RequestParam(defaultValue = DingTalkSyncLogService.TYPE_ATTENDANCE) String syncType,
        PageQuery pageQuery) {
        return R.ok(syncLogService.page(syncType, pageQuery));
    }

    /**
     * 总开关校验：未启用时给出明确提示，避免误以为接口不存在。
     */
    private void assertEnabled() {
        if (!configProvider.enabled()) {
            throw new ServiceException("钉钉同步未启用，请在「参数管理」开启 panjia.dingtalk.enabled");
        }
    }

    /**
     * 当前登录用户 ID（定时任务场景不经过本控制器）。
     */
    private Long currentUserId() {
        try {
            return LoginHelper.getUserId();
        } catch (Exception e) {
            return null;
        }
    }
}
