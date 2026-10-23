package com.panjia.payroll.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.panjia.payroll.domain.SelfPurchase;
import com.panjia.payroll.service.ISelfPurchaseService;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.dromara.common.log.annotation.Log;
import org.dromara.common.log.enums.BusinessType;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.common.web.core.BaseController;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 经纪人自购单提成：登记（合同号 + 经纪人）→ warm-flow 自购单提成审批
 * （self_purchase_approval）→ 总监在「我的待办」办理 → 办结回写状态；
 * 审批通过（APPROVED）后在算薪时对该经纪人在该合同号上的结佣提点额外 +bonus（默认 +10 个百分点）。
 * <p>
 * 审批动作（通过/驳回）全部经「我的待办」由引擎按 flow_user 名单判权办理，
 * 本控制器不提供业务直批端点。
 */
@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/payroll/selfpurchase")
public class SelfPurchaseController extends BaseController {

    private final ISelfPurchaseService selfPurchaseService;

    /** 列表查询（contractNo/employeeId/status 过滤） */
    @SaCheckPermission("payroll:selfpurchase:list")
    @GetMapping("/list")
    public R<List<SelfPurchase>> list(@RequestParam(required = false) String contractNo,
                                      @RequestParam(required = false) Long employeeId,
                                      @RequestParam(required = false) String status) {
        return R.ok(selfPurchaseService.list(contractNo, employeeId, status));
    }

    /** 按自购单 ID 查询（工作流办理弹窗详情组件用） */
    @SaCheckPermission("payroll:selfpurchase:list")
    @GetMapping("/detail/{id}")
    public R<SelfPurchase> getById(@PathVariable Long id) {
        return R.ok(selfPurchaseService.getById(id));
    }

    /** 登记自购单（DRAFT） */
    @SaCheckPermission("payroll:selfpurchase:add")
    @Log(title = "自购单提成", businessType = BusinessType.INSERT)
    @PostMapping
    public R<Long> add(@RequestBody SelfPurchase item) {
        return R.ok(selfPurchaseService.create(item, LoginHelper.getUserId()));
    }

    /** 修改（仅待提交/已驳回） */
    @SaCheckPermission("payroll:selfpurchase:add")
    @Log(title = "自购单提成", businessType = BusinessType.UPDATE)
    @PutMapping
    public R<Void> edit(@RequestBody SelfPurchase item) {
        selfPurchaseService.update(item, LoginHelper.getUserId());
        return R.ok();
    }

    /** 删除（仅待提交/已驳回） */
    @SaCheckPermission("payroll:selfpurchase:add")
    @Log(title = "自购单提成", businessType = BusinessType.DELETE)
    @DeleteMapping("/{id}")
    public R<Void> remove(@PathVariable Long id) {
        selfPurchaseService.delete(id, LoginHelper.getUserId());
        return R.ok();
    }

    /** 提交审批（发起/重提 warm-flow 流程） */
    @SaCheckPermission("payroll:selfpurchase:add")
    @Log(title = "自购单提成", businessType = BusinessType.UPDATE)
    @PostMapping("/submit")
    public R<Void> submit(@RequestBody Map<String, Long> body) {
        selfPurchaseService.submit(body.get("id"), LoginHelper.getUserId());
        return R.ok("已提交总监审批");
    }

    /** 撤销（审批中撤回流程回待提交；已通过作废终态不再生效） */
    @SaCheckPermission("payroll:selfpurchase:cancel")
    @Log(title = "自购单提成", businessType = BusinessType.UPDATE)
    @PostMapping("/cancel")
    public R<Void> cancel(@RequestBody Map<String, Long> body) {
        selfPurchaseService.cancel(body.get("id"), LoginHelper.getUserId());
        return R.ok();
    }

    /** 认领当前节点待办（自购单提成总监节点） */
    @SaCheckPermission("payroll:selfpurchase:list")
    @Log(title = "自购单提成任务认领", businessType = BusinessType.UPDATE)
    @PostMapping("/{id}/claim")
    public R<Boolean> claim(@PathVariable Long id) {
        return selfPurchaseService.claim(id)
            ? R.ok("认领成功", true)
            : R.fail("该自购单当前无待办，或您不具备当前节点所需角色", false);
    }
}
