package com.panjia.payroll.controller;

import com.panjia.payroll.domain.PayrollBatch;
import com.panjia.payroll.domain.PayrollDetail;
import com.panjia.payroll.domain.RuleSnapshot;
import com.panjia.payroll.service.PayrollBatchService;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.dromara.common.satoken.utils.LoginHelper;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 工资批次管理。
 */
@RestController
@RequestMapping("/payroll/batch")
@RequiredArgsConstructor
public class PayrollController {

    private final PayrollBatchService batchService;

    /** 创建批次 */
    @PostMapping
    public R<PayrollBatch> create(@RequestBody Map<String, String> body) {
        String period = body.get("period");
        String deptScope = body.getOrDefault("deptScope", "ALL");
        return R.ok(batchService.createBatch(period, deptScope, LoginHelper.getUserId()));
    }

    /** 算薪 */
    @PostMapping("/{id}/calculate")
    public R<PayrollBatch> calculate(@PathVariable Long id) {
        return R.ok(batchService.calculate(id, LoginHelper.getUserId()));
    }

    /** 提交审核 */
    @PostMapping("/{id}/submit")
    public R<PayrollBatch> submit(@PathVariable Long id) {
        return R.ok(batchService.submit(id, LoginHelper.getUserId()));
    }

    /** 审批通过 */
    @PostMapping("/{id}/approve")
    public R<PayrollBatch> approve(@PathVariable Long id) {
        return R.ok(batchService.approve(id, LoginHelper.getUserId()));
    }

    /** 驳回 */
    @PostMapping("/{id}/reject")
    public R<PayrollBatch> reject(@PathVariable Long id) {
        return R.ok(batchService.reject(id, LoginHelper.getUserId()));
    }

    /** 锁定 */
    @PostMapping("/{id}/lock")
    public R<PayrollBatch> lock(@PathVariable Long id) {
        return R.ok(batchService.lock(id, LoginHelper.getUserId()));
    }

    /** 标记发放 */
    @PostMapping("/{id}/pay")
    public R<PayrollBatch> pay(@PathVariable Long id) {
        return R.ok(batchService.pay(id, LoginHelper.getUserId()));
    }

    /** 批次列表 */
    @GetMapping
    public R<List<PayrollBatch>> list(@RequestParam(required = false) String period) {
        return R.ok(batchService.list(period));
    }

    /** 批次详情 */
    @GetMapping("/{id}")
    public R<PayrollBatch> detail(@PathVariable Long id) {
        return R.ok(batchService.get(id));
    }

    /** 工资明细 */
    @GetMapping("/{id}/details")
    public R<List<PayrollDetail>> details(@PathVariable Long id) {
        return R.ok(batchService.listDetails(id));
    }

    /** 规则快照 */
    @GetMapping("/{id}/snapshot")
    public R<RuleSnapshot> snapshot(@PathVariable Long id) {
        return R.ok(batchService.getRuleSnapshot(id));
    }
}
