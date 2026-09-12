package com.panjia.commission.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.panjia.commission.domain.CommissionApplication;
import com.panjia.commission.domain.CommissionItem;
import com.panjia.commission.dto.ApplyCreateDTO;
import com.panjia.commission.dto.ApplyQuery;
import com.panjia.commission.dto.CallbackDTO;
import com.panjia.commission.service.CommissionApplicationService;
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

import java.util.List;
import java.util.Map;

/**
 * 结佣申请单管理（发起 / 增量重拉 / 提交 / 审批锁定）。
 * <p>
 * 口径：确认"本月实收业绩可以进入工资"并审批锁定；锁定的结果不随上游业绩变动而变动。
 */
@Slf4j
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/commission/apply")
public class CommissionApplyController extends BaseController {

    private final CommissionApplicationService applicationService;

    /**
     * 分页查询申请单列表。
     *
     * @param query     筛选条件
     * @param pageQuery 分页参数
     * @return 申请单分页
     */
    @SaCheckPermission("commission:apply:list")
    @GetMapping("/list")
    public R<PageResult<CommissionApplication>> list(ApplyQuery query, PageQuery pageQuery) {
        return R.ok(applicationService.listApplications(query, pageQuery));
    }

    /**
     * 申请单详情（含明细）。
     *
     * @param id 申请单 ID
     * @return 申请单 + 明细
     */
    @SaCheckPermission("commission:apply:query")
    @GetMapping("/{id}")
    public R<Map<String, Object>> getInfo(@PathVariable Long id) {
        CommissionApplication application = applicationService.getApplication(id);
        List<CommissionItem> items = applicationService.listItems(id);
        return R.ok(Map.of("application", application, "items", items));
    }

    /**
     * 发起结佣（门店 + 月）。
     * <p>
     * 幂等：已有未审批单提示走增量重拉；已审批/锁定拒绝，变更走调整单。
     *
     * @param dto 发起请求（period + deptId）
     * @return 申请单 ID
     */
    @SaCheckPermission("commission:apply:add")
    @Log(title = "结佣申请单", businessType = BusinessType.INSERT)
    @PostMapping
    public R<Long> add(@Validated @RequestBody ApplyCreateDTO dto) {
        CommissionApplication application = applicationService.apply(
            dto.getPeriod(), dto.getDeptId(), LoginHelper.getUserId());
        return R.ok("发起成功", application.getId());
    }

    /**
     * 提交审批：DRAFT → SUBMITTED。
     *
     * @param id 申请单 ID
     * @return 操作结果
     */
    @SaCheckPermission("commission:apply:submit")
    @Log(title = "结佣申请单提交", businessType = BusinessType.UPDATE)
    @PostMapping("/{id}/submit")
    public R<Void> submit(@PathVariable Long id) {
        applicationService.submit(id, LoginHelper.getUserId());
        return R.ok();
    }

    /**
     * 增量重拉（仅 DRAFT/SUBMITTED；追加未入单的 amount>0 实收事实，幂等，§4.1.1）。
     *
     * @param id 申请单 ID
     * @return 操作结果
     */
    @SaCheckPermission("commission:apply:refresh")
    @Log(title = "结佣增量重拉", businessType = BusinessType.UPDATE)
    @PostMapping("/{id}/refresh")
    public R<Void> refresh(@PathVariable Long id) {
        applicationService.refresh(id, LoginHelper.getUserId());
        return R.ok();
    }

    /**
     * 审批回调（简化审批，单事务完成 SUBMITTED → LOCKED / REJECTED）。
     * <p>
     * 通过时落 approved_month = 当前月（工资归属月），明细 PENDING → APPROVED，
     * 发布 CommissionApprovedEvent 供 payroll 消费。
     *
     * @param id  申请单 ID
     * @param dto 审批结论（approve）
     * @return 操作结果
     */
    @SaCheckPermission("commission:apply:approve")
    @Log(title = "结佣申请单审批", businessType = BusinessType.UPDATE)
    @PostMapping("/{id}/callback")
    public R<Void> callback(@PathVariable Long id, @Validated @RequestBody CallbackDTO dto) {
        applicationService.callback(id, Boolean.TRUE.equals(dto.getApprove()), LoginHelper.getUserId());
        return R.ok();
    }

    /**
     * 作废申请单：仅 DRAFT/SUBMITTED 可作废。
     *
     * @param id 申请单 ID
     * @return 操作结果
     */
    @SaCheckPermission("commission:apply:cancel")
    @Log(title = "结佣申请单作废", businessType = BusinessType.UPDATE)
    @PostMapping("/{id}/cancel")
    public R<Void> cancel(@PathVariable Long id) {
        applicationService.cancel(id, LoginHelper.getUserId());
        return R.ok();
    }
}
