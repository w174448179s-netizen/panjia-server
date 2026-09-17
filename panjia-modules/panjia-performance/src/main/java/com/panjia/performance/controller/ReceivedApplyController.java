package com.panjia.performance.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.panjia.contracts.port.ApprovalAction;
import com.panjia.performance.domain.ReceivedApply;
import com.panjia.performance.dto.BatchApproveByContractRequest;
import com.panjia.performance.dto.BatchApproveResultDTO;
import com.panjia.performance.dto.ReceivedApplyQuery;
import com.panjia.performance.service.ReceivedApplyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.domain.PageResult;
import org.dromara.common.core.domain.R;
import org.dromara.common.log.annotation.Log;
import org.dromara.common.log.enums.BusinessType;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.web.core.BaseController;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 实收业绩审批（合同维度，§2 实收业绩流程）。
 * <p>
 * 导入后有实收自动建单提交；店长/财务/总监可手工提交（发起人路由）；
 * 支持单个审批与 Excel（合同号+金额匹配）批量审批。
 */
@Slf4j
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/performance/received")
public class ReceivedApplyController extends BaseController {

    private final ReceivedApplyService receivedApplyService;

    /** 分页查询实收审批单 */
    @SaCheckPermission("perf:received:list")
    @GetMapping("/list")
    public R<PageResult<ReceivedApply>> list(ReceivedApplyQuery query, PageQuery pageQuery) {
        return R.ok(receivedApplyService.list(query, pageQuery));
    }

    /** 详情（含合同下每人实收事实） */
    @SaCheckPermission("perf:received:query")
    @GetMapping("/{id}")
    public R<ReceivedApplyService.ReceivedApplyDetail> getInfo(@PathVariable Long id) {
        return R.ok(receivedApplyService.getDetail(id));
    }

    /**
     * 手工提交（无单自动建单）：按发起人角色路由。
     * Body: {"period":"2026-08","contractNo":"..."}
     */
    @SaCheckPermission("perf:received:submit")
    @Log(title = "实收业绩审批提交", businessType = BusinessType.UPDATE)
    @PostMapping("/submit")
    public R<Long> submit(@RequestBody Map<String, String> body) {
        ReceivedApply apply = receivedApplyService.manualSubmit(body.get("period"), body.get("contractNo"));
        return R.ok("提交成功", apply.getId());
    }

    /** 驳回后重新提交 */
    @SaCheckPermission("perf:received:submit")
    @Log(title = "实收业绩审批重新提交", businessType = BusinessType.UPDATE)
    @PostMapping("/{id}/resubmit")
    public R<Void> resubmit(@PathVariable Long id) {
        receivedApplyService.resubmit(id);
        return R.ok();
    }

    /** 作废 */
    @SaCheckPermission("perf:received:cancel")
    @Log(title = "实收业绩审批作废", businessType = BusinessType.UPDATE)
    @PostMapping("/{id}/cancel")
    public R<Void> cancel(@PathVariable Long id) {
        receivedApplyService.cancel(id);
        return R.ok();
    }

    /**
     * 按合同号批量审批（线程池异步执行，Spring MVC 通过 CompletableFuture 挂起请求等待完成）。
     * <p>逐单办理，去重 + 跳过已审批（非 SUBMITTED 或无待办）。
     * 前端请求超时设 5 分钟，期间显示 loading；完成后返回每张单的处理结果。
     */
    @SaCheckPermission("perf:received:batch")
    @Log(title = "实收业绩批量审批", businessType = BusinessType.UPDATE)
    @PostMapping("/batch-approve-by-contract-async")
    public CompletableFuture<R<BatchApproveResultDTO>> batchApproveByContractAsync(@RequestBody BatchApproveByContractRequest request) {
        return receivedApplyService.batchApproveByContractAsync(request.getPeriod(), request.getContractNos())
            .thenApply(result -> R.ok(
                "批量审批完成：成功 " + result.getSuccess() + " 个，跳过 " + result.getSkipped()
                    + " 个，失败 " + result.getFailed() + " 个",
                result))
            .exceptionally(ex -> {
                log.error("[实收批量审批] 异步处理异常", ex);
                return R.fail("批量审批处理异常：" + ex.getCause().getMessage());
            });
    }

    /**
     * 业务明细直接审批（双入口 §三）：从实收审批单详情页直接审批，与「我的待办」共用同一审批服务。
     * <p>设计文档 §3.3 三条底线：
     * <ol>
     *   <li>调用同一 {@link com.panjia.performance.service.ReceivedApplyService#approve} 方法，留痕一致；</li>
     *   <li>服务端鉴权由 {@code completeTaskAsLoginUser} 走流程引擎原生权限校验；</li>
     *   <li>非当前节点审批人 → 服务端拒绝（前端隐藏按钮 ≠ 安全）。</li>
     * </ol>
     *
     * @param id     审批单 ID
     * @param action 审批动作（PASS / REJECT）
     * @param comment 审批意见（可选，留空时按动作给默认值）
     */
    @SaCheckPermission("perf:received:approve")
    @Log(title = "实收审批单审批", businessType = BusinessType.UPDATE)
    @PostMapping("/{id}/approve")
    public R<Void> approve(@PathVariable Long id,
                           @RequestParam ApprovalAction action,
                           @RequestParam(required = false) String comment) {
        receivedApplyService.approve(id, action, comment);
        return R.ok();
    }
}
