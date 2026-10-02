package com.panjia.performance.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.panjia.performance.service.IPeriodCloseService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.domain.R;
import org.dromara.common.log.annotation.Log;
import org.dromara.common.log.enums.BusinessType;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.common.web.core.BaseController;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 期间解封（反结账）。
 * <p>
 * 封账由算薪批次「总监锁定」自动完成，不提供手工封账/列表入口。
 * 本端点仅供结佣明细页在已封账期间需要纠错时解封使用。
 */
@Slf4j
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/perf/period")
public class PerformancePeriodController extends BaseController {

    private final IPeriodCloseService periodCloseService;

    /**
     * 反结账（解封）。
     * <p>反结账需强制录入原因，留痕审计（§3.5）。
     *
     * @param period 期间（YYYY-MM）
     * @param reason 反结账原因（必填）
     * @return 操作结果
     */
    @SaCheckPermission("perf:period:reopen")
    @Log(title = "期间反结账", businessType = BusinessType.UPDATE)
    @PostMapping("/reopen/{period}")
    public R<Void> reopen(@PathVariable String period,
                          @RequestParam String reason) {
        periodCloseService.reopenPeriod(period, reason, LoginHelper.getUserId());
        return R.ok();
    }
}
