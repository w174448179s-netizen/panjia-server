package com.panjia.performance.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.panjia.performance.domain.PerformanceAdjust;
import com.panjia.performance.dto.AdjustCreateDTO;
import com.panjia.performance.dto.AdjustQuery;
import com.panjia.performance.service.PerformanceAdjustService;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 业绩调整单管理。
 * <p>
 * 提供调整单的分页查询、详情查询、发起、审批通过、审批拒绝、取消、执行等接口。
 * 调整类型支持：金额调整（AMOUNT）、业绩冲销（VOID）、部门划转（TRANSFER）。
 */
@Slf4j
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/perf/adjust")
public class PerformanceAdjustController extends BaseController {

    private final PerformanceAdjustService adjustService;

    /**
     * 分页查询调整单列表。
     *
     * @param query     筛选条件
     * @param pageQuery 分页参数
     * @return 调整单分页
     */
    @SaCheckPermission("perf:adjust:list")
    @GetMapping("/list")
    public R<PageResult<PerformanceAdjust>> list(AdjustQuery query, PageQuery pageQuery) {
        return R.ok(adjustService.listAdjusts(query, pageQuery));
    }

    /**
     * 查询调整单详情。
     *
     * @param id 调整单 ID
     * @return 调整单详情
     */
    @SaCheckPermission("perf:adjust:query")
    @GetMapping("/{id}")
    public R<PerformanceAdjust> getInfo(@PathVariable Long id) {
        return R.ok(adjustService.getAdjust(id));
    }

    /**
     * 发起调整单。
     *
     * @param dto 调整单创建请求
     * @return 调整单 ID
     */
    @SaCheckPermission("perf:adjust:add")
    @Log(title = "业绩调整单", businessType = BusinessType.INSERT)
    @PostMapping
    public R<Long> add(@Validated @RequestBody AdjustCreateDTO dto) {
        PerformanceAdjust adjust = adjustService.createAdjust(dto, LoginHelper.getUserId());
        return R.ok("发起成功", adjust.getId());
    }

    /**
     * 审批通过调整单。
     *
     * @param id 调整单 ID
     * @return 操作结果
     */
    @SaCheckPermission("perf:adjust:approve")
    @Log(title = "业绩调整单审批", businessType = BusinessType.UPDATE)
    @PutMapping("/approve/{id}")
    public R<Void> approve(@PathVariable Long id) {
        adjustService.approveAdjust(id, LoginHelper.getUserId());
        return R.ok();
    }

    /**
     * 审批拒绝调整单。
     *
     * @param id     调整单 ID
     * @param reason 拒绝原因
     * @return 操作结果
     */
    @SaCheckPermission("perf:adjust:approve")
    @Log(title = "业绩调整单审批", businessType = BusinessType.UPDATE)
    @PutMapping("/reject/{id}")
    public R<Void> reject(@PathVariable Long id, @RequestParam String reason) {
        adjustService.rejectAdjust(id, LoginHelper.getUserId(), reason);
        return R.ok();
    }

    /**
     * 取消调整单。
     *
     * @param id 调整单 ID
     * @return 操作结果
     */
    @SaCheckPermission("perf:adjust:edit")
    @Log(title = "业绩调整单", businessType = BusinessType.UPDATE)
    @PutMapping("/cancel/{id}")
    public R<Void> cancel(@PathVariable Long id) {
        adjustService.cancelAdjust(id, LoginHelper.getUserId());
        return R.ok();
    }

    /**
     * 执行调整单。
     *
     * @param id 调整单 ID
     * @return 操作结果
     */
    @SaCheckPermission("perf:adjust:execute")
    @Log(title = "业绩调整单执行", businessType = BusinessType.UPDATE)
    @PutMapping("/execute/{id}")
    public R<Void> execute(@PathVariable Long id) {
        adjustService.executeAdjust(id, LoginHelper.getUserId());
        return R.ok();
    }
}
