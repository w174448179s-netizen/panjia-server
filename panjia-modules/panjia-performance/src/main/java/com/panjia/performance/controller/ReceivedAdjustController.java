package com.panjia.performance.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.panjia.performance.domain.ReceivedAdjust;
import com.panjia.performance.domain.bo.ReceivedAdjustBo;
import com.panjia.performance.domain.bo.ReceivedAdjustCreateBo;
import com.panjia.performance.domain.vo.AdjustDetailVo;
import com.panjia.performance.service.IReceivedAdjustService;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 实收调整单管理（pj_perf_received_adjust，2026-10-12 与新签调整物理拆表）。
 * <p>
 * 审批走工作流 flowCode = perf_received_adjust（申请人 → 总监）：
 * 发起即启动审批，审批通过后由工作流回调自动按分摊快照调整实收明细。
 * <p>
 * 权限码沿用 perf:adjust:*（实收调整菜单挂载同一组权限，避免菜单/角色权限迁移）。
 */
@Slf4j
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/perf/received-adjust")
public class ReceivedAdjustController extends BaseController {

    private final IReceivedAdjustService receivedAdjustService;

    /**
     * 分页查询实收调整单列表。
     */
    @SaCheckPermission("perf:adjust:list")
    @GetMapping("/list")
    public R<PageResult<ReceivedAdjust>> list(ReceivedAdjustBo query, PageQuery pageQuery) {
        return R.ok(receivedAdjustService.list(query, pageQuery));
    }

    /**
     * 实收调整列表「业务类型」下拉（数据范围与列表一致）。
     */
    @SaCheckPermission("perf:adjust:list")
    @GetMapping("/biz-types")
    public R<List<String>> bizTypes(ReceivedAdjustBo query) {
        return R.ok(receivedAdjustService.distinctBizTypes(query));
    }

    /**
     * 查询实收调整单完整详情（含合同信息 + 受影响实收明细与分摊预演）。
     */
    @SaCheckPermission("perf:adjust:query")
    @GetMapping("/{id}/detail")
    public R<AdjustDetailVo> getDetail(@PathVariable Long id) {
        return R.ok(receivedAdjustService.getDetail(id));
    }

    /**
     * 发起实收调整单并启动审批流程。
     *
     * @return 调整单 ID
     */
    @SaCheckPermission("perf:adjust:add")
    @Log(title = "实收调整单", businessType = BusinessType.INSERT)
    @PostMapping
    public R<Long> add(@Validated @RequestBody ReceivedAdjustCreateBo dto) {
        ReceivedAdjust adjust = receivedAdjustService.create(dto, LoginHelper.getUserId());
        return R.ok("发起成功，已提交审批", adjust.getId());
    }

    /**
     * 申请人撤回审批中的实收调整单（仅 SUBMITTED、仅发起人；流程实例一并删除，单据置 CANCELLED）。
     */
    @SaCheckPermission("perf:adjust:add")
    @Log(title = "实收调整单", businessType = BusinessType.UPDATE)
    @PostMapping("/{id}/withdraw")
    public R<Void> withdraw(@PathVariable Long id) {
        receivedAdjustService.withdraw(id, LoginHelper.getUserId());
        return R.ok("已撤回");
    }

    /**
     * 前端预检：指定合同在指定实收期间是否存在审批中的实收调整单。
     */
    @SaCheckPermission("perf:adjust:list")
    @GetMapping("/in-flight-check")
    public R<Boolean> checkInFlight(@RequestParam String contractNo,
                                    @RequestParam String period) {
        return R.ok(receivedAdjustService.hasInFlight(contractNo, period));
    }
}
