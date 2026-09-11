package com.panjia.performance.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.panjia.performance.domain.PerformancePeriodClose;
import com.panjia.performance.service.PeriodCloseService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.domain.R;
import org.dromara.common.log.annotation.Log;
import org.dromara.common.log.enums.BusinessType;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.common.web.core.BaseController;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 期间封账管理。
 * <p>
 * 提供期间列表查询、详情查询、封账、反结账等接口。
 * 封账后该期间的业绩事实不再允许新增、修改或冲销。
 */
@Slf4j
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/perf/period")
public class PerformancePeriodController extends BaseController {

    private final PeriodCloseService periodCloseService;

    /**
     * 查询期间列表。
     *
     * @return 期间封账记录列表
     */
    @SaCheckPermission("perf:period:list")
    @GetMapping("/list")
    public R<List<PerformancePeriodClose>> list() {
        return R.ok(periodCloseService.listPeriods());
    }

    /**
     * 查询期间详情。
     *
     * @param period 期间（YYYY-MM）
     * @return 期间封账记录
     */
    @SaCheckPermission("perf:period:query")
    @GetMapping("/{period}")
    public R<PerformancePeriodClose> getInfo(@PathVariable String period) {
        return R.ok(periodCloseService.getPeriod(period));
    }

    /**
     * 封账。
     *
     * @param period 期间（YYYY-MM）
     * @param reason 封账原因（可选）
     * @return 操作结果
     */
    @SaCheckPermission("perf:period:close")
    @Log(title = "期间封账", businessType = BusinessType.UPDATE)
    @PostMapping("/close/{period}")
    public R<Void> close(@PathVariable String period,
                         @RequestParam(required = false) String reason) {
        periodCloseService.closePeriod(period, reason, LoginHelper.getUserId());
        return R.ok();
    }

    /**
     * 反结账。
     *
     * @param period 期间（YYYY-MM）
     * @return 操作结果
     */
    @SaCheckPermission("perf:period:reopen")
    @Log(title = "期间反结账", businessType = BusinessType.UPDATE)
    @PostMapping("/reopen/{period}")
    public R<Void> reopen(@PathVariable String period) {
        periodCloseService.reopenPeriod(period, LoginHelper.getUserId());
        return R.ok();
    }
}
