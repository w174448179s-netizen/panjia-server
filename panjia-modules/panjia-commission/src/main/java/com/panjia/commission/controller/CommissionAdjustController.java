package com.panjia.commission.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.panjia.commission.domain.CommissionAdjust;
import com.panjia.commission.dto.AdjustCreateDTO;
import com.panjia.commission.dto.AdjustQuery;
import com.panjia.commission.service.CommissionAdjustService;
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

/**
 * 结佣调整单管理（DISCOUNT 折扣 / DIFF 差额补发 / VOID 作废）。
 * <p>
 * 审批全走 RuoYi 工作流（flowCode = commission_adjust）：
 * 发起调整单时自动启动审批流程，审批通过后由工作流回调自动执行调整。
 * 已审批结佣数据变更的唯一入口（V4.2 §9.4），不自动修复。
 */
@Slf4j
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/commission/adjust")
public class CommissionAdjustController extends BaseController {

    private final CommissionAdjustService adjustService;

    /**
     * 分页查询调整单列表。
     *
     * @param query     筛选条件
     * @param pageQuery 分页参数
     * @return 调整单分页
     */
    @SaCheckPermission("commission:adjust:list")
    @GetMapping("/list")
    public R<PageResult<CommissionAdjust>> list(AdjustQuery query, PageQuery pageQuery) {
        return R.ok(adjustService.listAdjusts(query, pageQuery));
    }

    /**
     * 调整单详情。
     *
     * @param id 调整单 ID
     * @return 调整单
     */
    @SaCheckPermission("commission:adjust:query")
    @GetMapping("/{id}")
    public R<CommissionAdjust> getInfo(@PathVariable Long id) {
        return R.ok(adjustService.getAdjust(id));
    }

    /**
     * 发起调整单（DISCOUNT / DIFF / VOID）。
     * <p>
     * 折扣不存系数：DISCOUNT 直接存折后金额（new_amount = 8500），reason 写"85折"供审计。
     *
     * @param dto 调整单创建请求
     * @return 调整单 ID
     */
    @SaCheckPermission("commission:adjust:add")
    @Log(title = "结佣调整单", businessType = BusinessType.INSERT)
    @PostMapping
    public R<Long> add(@Validated @RequestBody AdjustCreateDTO dto) {
        CommissionAdjust adjust = adjustService.create(dto, LoginHelper.getUserId());
        return R.ok("发起成功", adjust.getId());
    }

    /**
     * 取消调整单：仅 SUBMITTED 可取消。
     *
     * @param id 调整单 ID
     * @return 操作结果
     */
    @SaCheckPermission("commission:adjust:cancel")
    @Log(title = "结佣调整单取消", businessType = BusinessType.UPDATE)
    @PostMapping("/{id}/cancel")
    public R<Void> cancel(@PathVariable Long id) {
        adjustService.cancel(id, LoginHelper.getUserId());
        return R.ok();
    }
}
