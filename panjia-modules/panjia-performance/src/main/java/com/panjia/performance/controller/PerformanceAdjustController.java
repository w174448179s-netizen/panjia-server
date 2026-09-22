package com.panjia.performance.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.panjia.performance.domain.PerformanceAdjust;
import com.panjia.performance.domain.bo.PerformanceAdjustCreateBo;
import com.panjia.performance.domain.vo.AdjustDetailVo;
import com.panjia.performance.domain.bo.PerformanceAdjustBo;
import com.panjia.performance.service.IPerformanceAdjustService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.domain.PageResult;
import org.dromara.common.core.domain.R;
import org.dromara.common.log.annotation.Log;
import org.dromara.common.log.enums.BusinessType;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.common.web.core.BaseController;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 业绩调整单管理。
 * <p>
 * 审批全走 RuoYi 工作流（flowCode = perf_adjust）：
 * 发起调整单时自动启动审批流程，审批通过后由工作流回调自动执行调整。
 * 调整范围支持：合同级（按业绩比例分摊到各明细）、明细级（单条事实调整）。
 */
@Slf4j
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/perf/adjust")
public class PerformanceAdjustController extends BaseController {

    private final IPerformanceAdjustService adjustService;

    /**
     * 分页查询调整单列表。
     */
    @SaCheckPermission("perf:adjust:list")
    @GetMapping("/list")
    public R<PageResult<PerformanceAdjust>> list(PerformanceAdjustBo query, PageQuery pageQuery) {
        return R.ok(adjustService.listAdjusts(query, pageQuery));
    }

    /**
     * 查询调整单完整详情（含合同信息 + 受影响明细）。
     * <p>
     * 审批办理页使用，让审批人能看清调整的标的合同和影响范围。
     */
    @SaCheckPermission("perf:adjust:query")
    @GetMapping("/{id}/detail")
    public R<AdjustDetailVo> getDetail(@PathVariable Long id) {
        return R.ok(adjustService.getAdjustDetail(id));
    }

    /**
     * 发起调整单并启动审批流程。
     *
     * @param dto 调整创建条件（期间/事实口径/合同号/调整类型/调整金额/原因等）
     * @return 调整单 ID
     */
    @SaCheckPermission("perf:adjust:add")
    @Log(title = "业绩调整单", businessType = BusinessType.INSERT)
    @PostMapping
    public R<Long> add(@Validated @RequestBody PerformanceAdjustCreateBo dto) {
        PerformanceAdjust adjust = adjustService.createAdjust(dto, LoginHelper.getUserId());
        return R.ok("发起成功，已提交审批", adjust.getId());
    }

}
