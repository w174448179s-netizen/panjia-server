package com.panjia.performance.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.panjia.performance.domain.ReceivedApply;
import com.panjia.performance.dto.ReceivedApplyQuery;
import com.panjia.performance.dto.ReceivedBatchApproveResult;
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
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

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

    /** 审批通过（办理当前节点） */
    @SaCheckPermission("perf:received:approve")
    @Log(title = "实收业绩审批通过", businessType = BusinessType.UPDATE)
    @PostMapping("/{id}/approve")
    public R<Void> approve(@PathVariable Long id, @RequestBody(required = false) Map<String, String> body) {
        String message = body == null ? null : body.get("message");
        receivedApplyService.approve(id, message);
        return R.ok();
    }

    /** 驳回 */
    @SaCheckPermission("perf:received:approve")
    @Log(title = "实收业绩驳回", businessType = BusinessType.UPDATE)
    @PostMapping("/{id}/reject")
    public R<Void> reject(@PathVariable Long id, @RequestBody(required = false) Map<String, String> body) {
        String message = body == null ? null : body.get("message");
        receivedApplyService.reject(id, message);
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
     * Excel 批量审批（合同号 + 实收金额 匹配）。
     */
    @SaCheckPermission("perf:received:batch")
    @Log(title = "实收业绩Excel批量审批", businessType = BusinessType.IMPORT)
    @PostMapping("/batch-approve")
    public R<ReceivedBatchApproveResult> batchApprove(@RequestParam("file") MultipartFile file,
                                                      @RequestParam("period") String period) {
        return R.ok(receivedApplyService.batchApprove(period, file));
    }
}
