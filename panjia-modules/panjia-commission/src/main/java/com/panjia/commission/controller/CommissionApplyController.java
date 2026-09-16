package com.panjia.commission.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.panjia.commission.domain.CommissionApplication;
import com.panjia.commission.dto.ApplyCreateDTO;
import com.panjia.commission.dto.ApplyQuery;
import com.panjia.commission.dto.CommissionBatchResult;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

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
     * 批量发起结佣：为期间内所有未发起且有非零实收的合同逐张建草稿单。
     *
     * @param dto 批量请求（period 必填，deptId 可选）
     * @return 新创建申请单数量
     */
    @SaCheckPermission("commission:apply:add")
    @Log(title = "结佣批量发起", businessType = BusinessType.INSERT)
    @PostMapping("/batch")
    public R<Integer> batchAdd(@Validated @RequestBody ApplyCreateDTO dto) {
        int created = applicationService.batchApply(
            dto.getPeriod(), dto.getDeptId(), LoginHelper.getUserId());
        return R.ok("批量发起完成，共创建 " + created + " 张申请单", created);
    }

    /**
     * Excel 批量发起（§3.2）：按表内合同号逐张发起并自动提交。
     *
     * @param file   Excel（含「合同号」列，金额列可选）
     * @param period 业绩归属月 YYYY-MM
     * @return 成功/失败明细
     */
    @SaCheckPermission("commission:apply:batch")
    @Log(title = "结佣Excel批量发起", businessType = BusinessType.IMPORT)
    @PostMapping("/batch-initiate")
    public R<CommissionBatchResult> batchInitiate(@RequestParam("file") MultipartFile file,
                                                  @RequestParam("period") String period) {
        return R.ok(applicationService.batchInitiate(period, file, LoginHelper.getUserId()));
    }

    /**
     * Excel 批量审批（§3.3）：匹配 合同号+金额 与审批中单据，按当前节点逐张通过。
     *
     * @param file   Excel（含「合同号」+实收金额列）
     * @param period 业绩归属月 YYYY-MM
     * @return 成功/失败明细
     */
    @SaCheckPermission("commission:apply:batch")
    @Log(title = "结佣Excel批量审批", businessType = BusinessType.IMPORT)
    @PostMapping("/batch-approve")
    public R<CommissionBatchResult> batchApprove(@RequestParam("file") MultipartFile file,
                                                 @RequestParam("period") String period) {
        return R.ok(applicationService.batchApprove(period, file));
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
}
