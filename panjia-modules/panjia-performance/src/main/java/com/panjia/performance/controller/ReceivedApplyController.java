package com.panjia.performance.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.panjia.performance.domain.ReceivedApply;
import com.panjia.performance.domain.bo.ReceivedBatchApproveBo;
import com.panjia.performance.domain.vo.BatchApproveResultVo;
import com.panjia.performance.domain.bo.ReceivedApplyBo;
import com.panjia.performance.service.IReceivedApplyService;
import com.panjia.performance.service.PerformanceEngine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.domain.PageResult;
import org.dromara.common.core.domain.R;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.log.annotation.Log;
import org.dromara.common.log.enums.BusinessType;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.web.core.BaseController;
import org.dromara.common.satoken.utils.LoginHelper;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
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

    private final IReceivedApplyService receivedApplyService;
    private final PerformanceEngine performanceEngine;

    /** 分页查询实收审批单 */
    @SaCheckPermission("perf:received:list")
    @GetMapping("/list")
    public R<PageResult<ReceivedApply>> list(ReceivedApplyBo query, PageQuery pageQuery) {
        return R.ok(receivedApplyService.list(query, pageQuery));
    }

    /** 详情（含合同下每人实收事实） */
    @SaCheckPermission("perf:received:query")
    @GetMapping("/{id}")
    public R<IReceivedApplyService.ReceivedApplyDetail> getInfo(@PathVariable Long id) {
        return R.ok(receivedApplyService.getDetail(id));
    }

    /** 按审批单 ID 查流程实例 ID（供前端「业务明细直批」绕过 workflow:instance:query 权限） */
    @SaCheckPermission("perf:received:query")
    @GetMapping("/{id}/instance")
    public R<Map<String, Object>> getInstance(@PathVariable Long id) {
        return R.ok(Map.of("instanceId", receivedApplyService.getInstanceId(id)));
    }

    /** 驳回后重新提交 */
    @SaCheckPermission("perf:received:submit")
    @Log(title = "实收业绩审批重新提交", businessType = BusinessType.UPDATE)
    @PostMapping("/{id}/resubmit")
    public R<Void> resubmit(@PathVariable Long id) {
        receivedApplyService.resubmit(id);
        return R.ok();
    }

    /**
     * 手工批量提交实收：选合同 → 后端查 PERF_EXPECT → 镜像造 PERF_REAL → 按订单号分组建审批单。
     * Body: {"period":"2026-08", "bizKeys":["合同号1","合同号2"]}
     */
    @SaCheckPermission("perf:received:submit")
    @Log(title = "实收业绩手工批量提交", businessType = BusinessType.INSERT)
    @PostMapping("/manual-batch-submit")
    public R<Map<String, Object>> manualBatchSubmit(@RequestBody Map<String, Object> body) {
        String period = (String) body.get("period");
        @SuppressWarnings("unchecked")
        List<String> bizKeys = (List<String>) body.get("bizKeys");
        if (StringUtils.isBlank(period) || bizKeys == null || bizKeys.isEmpty()) {
            return R.fail("period 与 bizKeys 必填");
        }
        PerformanceEngine.ManualSubmitResult result = performanceEngine.submitManualReceived(
            bizKeys, period, LoginHelper.getUserId());
        Map<String, Object> resp = new java.util.LinkedHashMap<>();
        resp.put("createdRealCount", result.createdRealCount);
        resp.put("createdApplyCount", result.createdApplyCount);
        resp.put("skipped", result.skippedReasons);
        return R.ok("提交完成", resp);
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
    public CompletableFuture<R<BatchApproveResultVo>> batchApproveByContractAsync(@RequestBody ReceivedBatchApproveBo request) {
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
}
