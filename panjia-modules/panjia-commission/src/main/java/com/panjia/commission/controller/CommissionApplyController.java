package com.panjia.commission.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.panjia.commission.domain.CommissionApplication;
import com.panjia.commission.dto.ApplyCreateDTO;
import com.panjia.commission.dto.ApplyQuery;
import com.panjia.commission.dto.BatchApproveByContractRequest;
import com.panjia.commission.dto.BatchResultDTO;
import com.panjia.commission.service.CommissionApplicationService;
import com.panjia.contracts.port.ApprovalAction;
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
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 结佣申请单管理（按合同发起 / 提交 / 审批锁定）。
 * <p>
 * 申请单粒度 = 合同 + 月：确认"该合同本月实收业绩可以进入工资"并审批锁定；
 * 锁定的结果不随上游业绩变动而变动。
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
     * 按「合同」维度分页查询结佣申请（列表页合同维度展示用，含未发起合同）。
     *
     * @param query     筛选条件
     * @param pageQuery 分页参数
     * @return 合同维度分页
     */
    @SaCheckPermission("commission:apply:list")
    @GetMapping("/contract-list")
    public R<PageResult<com.panjia.commission.dto.CommissionContractVO>> contractList(ApplyQuery query,
                                                                                      PageQuery pageQuery) {
        return R.ok(applicationService.listContracts(query, pageQuery));
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
        List<com.panjia.commission.dto.CommissionItemDetailDTO> items = applicationService.listItemDetails(id);
        return R.ok(Map.of("application", application, "items", items));
    }

    /** 按申请单 ID 查流程实例 ID（供前端「业务明细直批」绕过 workflow:instance:query 权限） */
    @SaCheckPermission("commission:apply:query")
    @GetMapping("/{id}/instance")
    public R<Map<String, Object>> getInstance(@PathVariable Long id) {
        return R.ok(Map.of("instanceId", applicationService.getInstanceId(id)));
    }

    /**
     * 发起结佣（合同 + 月）：一次操作完成发起并提交审批，直接进入审批流。
     * <p>
     * 幂等：该合同当月已有 DRAFT/SUBMITTED/APPROVED/LOCKED 单时拒绝；
     * 已有 REJECTED（驳回）单时直接重新提交该单进入审批流，不新建；
     * 已审批/锁定拒绝，变更走调整单。
     *
     * @param dto 发起请求（period + contractNo）
     * @return 申请单 ID
     */
    @SaCheckPermission("commission:apply:add")
    @Log(title = "结佣申请单", businessType = BusinessType.INSERT)
    @PostMapping
    public R<Long> add(@Validated @RequestBody ApplyCreateDTO dto) {
        CommissionApplication application = applicationService.apply(
            dto.getPeriod(), dto.getContractNo(), LoginHelper.getUserId());
        return R.ok("发起成功", application.getId());
    }

    /**
     * 按合同号批量发起结佣（CompletableFuture 挂起等待，线程池逐张发起+提交）。
     * <p>去重合同号，已有未完结单的跳过，REJECTED 自动重提。前端设 5 分钟超时 + loading。
     *
     * @param request 批量发起请求（period + contractNos）
     * @return 批量发起结果
     */
    @SaCheckPermission("commission:apply:add")
    @Log(title = "结佣批量发起", businessType = BusinessType.INSERT)
    @PostMapping("/batch-apply-by-contract")
    public CompletableFuture<R<BatchResultDTO>> batchApplyByContract(@RequestBody BatchApproveByContractRequest request) {
        return applicationService.batchApplyByContract(
                request.getPeriod(), request.getContractNos(), LoginHelper.getUserId())
            .thenApply(result -> R.ok(
                "批量发起完成：成功 " + result.getSuccess() + " 个，跳过 " + result.getSkipped()
                    + " 个，失败 " + result.getFailed() + " 个",
                result))
            .exceptionally(ex -> {
                log.error("[结佣批量发起] 异步处理异常", ex);
                return R.fail("批量发起处理异常：" + ex.getCause().getMessage());
            });
    }

    /**
     * 按合同号批量审批（CompletableFuture 挂起等待，线程池逐单办理当前待办节点）。
     * <p>去重合同号，非 SUBMITTED 或无待办任务的跳过。前端设 5 分钟超时 + loading。
     *
     * @param request 批量审批请求（period + contractNos）
     * @return 批量审批结果
     */
    @SaCheckPermission("commission:apply:batch")
    @Log(title = "结佣批量审批", businessType = BusinessType.UPDATE)
    @PostMapping("/batch-approve-by-contract")
    public CompletableFuture<R<BatchResultDTO>> batchApproveByContract(@RequestBody BatchApproveByContractRequest request) {
        return applicationService.batchApproveByContract(
                request.getPeriod(), request.getContractNos(), LoginHelper.getUserId())
            .thenApply(result -> R.ok(
                "批量审批完成：成功 " + result.getSuccess() + " 个，跳过 " + result.getSkipped()
                    + " 个，失败 " + result.getFailed() + " 个",
                result))
            .exceptionally(ex -> {
                log.error("[结佣批量审批] 异步处理异常", ex);
                return R.fail("批量审批处理异常：" + ex.getCause().getMessage());
            });
    }

    /**
     * 作废申请单：仅 DRAFT/SUBMITTED 可作废，未审批明细随单冲销。
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

    /**
     * 业务明细直接审批（双入口 §三）：从结佣申请单详情页直接审批，与「我的待办」共用同一审批服务。
     * <p>设计文档 §3.3 三条底线：
     * <ol>
     *   <li>调用同一 {@link CommissionApplicationService#approve} 方法，留痕一致；</li>
     *   <li>服务端鉴权由 {@code completeTaskAsLoginUser} 走流程引擎原生权限校验；</li>
     *   <li>非当前节点审批人 → 服务端拒绝（前端隐藏按钮 ≠ 安全）。</li>
     * </ol>
     *
     * @param id     申请单 ID
     * @param action 审批动作（PASS / REJECT）
     * @param comment 审批意见（可选，留空时按节点+动作给默认值）
     */
    @SaCheckPermission("commission:apply:approve")
    @Log(title = "结佣申请单审批", businessType = BusinessType.UPDATE)
    @PostMapping("/{id}/approve")
    public R<Void> approve(@PathVariable Long id,
                           @RequestParam ApprovalAction action,
                           @RequestParam(required = false) String comment) {
        applicationService.approve(id, action, comment);
        return R.ok();
    }
}
