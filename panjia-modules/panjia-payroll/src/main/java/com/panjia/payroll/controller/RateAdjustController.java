package com.panjia.payroll.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.panjia.payroll.domain.RateAdjust;
import com.panjia.payroll.service.IRateAdjustService;
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
 * 提成点调整：财务登记员工业绩扣点调整（原因必填）→ warm-flow 提成点调整审批
 * （rate_adjust_approval）→ 总监在「我的待办」办理 → 办结回写状态；
 * 审批通过（APPROVED）的调整按生效区间在算薪时自动叠加到提成比例。
 * <p>
 * 审批动作（通过/驳回）全部经「我的待办」由引擎按 flow_user 名单判权办理，
 * 本控制器不提供业务直批端点。
 */
@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/payroll/rateadjust")
public class RateAdjustController extends BaseController {

    private final IRateAdjustService rateAdjustService;

    /** 列表查询（employeeId/adjustType/status 过滤；period 传则仅查该月生效中的调整） */
    @SaCheckPermission("payroll:rateadjust:list")
    @GetMapping("/list")
    public R<List<RateAdjust>> list(@RequestParam(required = false) Long employeeId,
                                    @RequestParam(required = false) String adjustType,
                                    @RequestParam(required = false) String status,
                                    @RequestParam(required = false) String period) {
        return R.ok(rateAdjustService.list(employeeId, adjustType, status, period));
    }

    /** 按调整单 ID 查询（工作流办理弹窗详情组件用） */
    @SaCheckPermission("payroll:rateadjust:list")
    @GetMapping("/detail/{id}")
    public R<RateAdjust> getById(@PathVariable Long id) {
        return R.ok(rateAdjustService.getById(id));
    }

    /** 登记调整单（DRAFT） */
    @SaCheckPermission("payroll:rateadjust:add")
    @Log(title = "提成点调整", businessType = BusinessType.INSERT)
    @PostMapping
    public R<Long> add(@RequestBody RateAdjust item) {
        return R.ok(rateAdjustService.create(item, LoginHelper.getUserId()));
    }

    /** 修改（仅待提交/已驳回） */
    @SaCheckPermission("payroll:rateadjust:add")
    @Log(title = "提成点调整", businessType = BusinessType.UPDATE)
    @PutMapping
    public R<Void> edit(@RequestBody RateAdjust item) {
        rateAdjustService.update(item, LoginHelper.getUserId());
        return R.ok();
    }

    /** 删除（仅待提交/已驳回） */
    @SaCheckPermission("payroll:rateadjust:add")
    @Log(title = "提成点调整", businessType = BusinessType.DELETE)
    @DeleteMapping("/{id}")
    public R<Void> remove(@PathVariable Long id) {
        rateAdjustService.delete(id, LoginHelper.getUserId());
        return R.ok();
    }

    /** 提交审批（发起/重提 warm-flow 流程） */
    @SaCheckPermission("payroll:rateadjust:add")
    @Log(title = "提成点调整", businessType = BusinessType.UPDATE)
    @PostMapping("/submit")
    public R<Void> submit(@RequestBody Map<String, Long> body) {
        rateAdjustService.submit(body.get("id"), LoginHelper.getUserId());
        return R.ok("已提交总监审批");
    }

    /** 撤销（审批中撤回流程回待提交；已通过作废终态不再生效） */
    @SaCheckPermission("payroll:rateadjust:cancel")
    @Log(title = "提成点调整", businessType = BusinessType.UPDATE)
    @PostMapping("/cancel")
    public R<Void> cancel(@RequestBody Map<String, Long> body) {
        rateAdjustService.cancel(body.get("id"), LoginHelper.getUserId());
        return R.ok();
    }
}
