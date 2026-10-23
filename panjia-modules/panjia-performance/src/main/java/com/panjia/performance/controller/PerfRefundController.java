package com.panjia.performance.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.panjia.performance.domain.PerfRefund;
import com.panjia.performance.domain.bo.PerfRefundBo;
import com.panjia.performance.domain.bo.PerfRefundCreateBo;
import com.panjia.performance.service.IPerfRefundService;
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

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * 退单管理（pj_perf_refund）。
 * <p>
 * 退单以合同为单位全额冲退：审批通过后生成负新签 + 负实收流水（归属月=退单月），
 * 该单据即「合同已退单」状态源，退单统计直接查本接口列表/汇总。
 * 与「业绩冲正（MANUAL_OFFSET）」不同：退单不可撤销，且会阻断同合同重复退单。
 */
@Slf4j
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/perf/refund")
public class PerfRefundController extends BaseController {

    private final IPerfRefundService refundService;

    /** 分页查询退单列表 */
    @SaCheckPermission("perf:refund:list")
    @GetMapping("/list")
    public R<PageResult<PerfRefund>> list(PerfRefundBo query, PageQuery pageQuery) {
        return R.ok(refundService.list(query, pageQuery));
    }

    /** 退单详情（审批弹窗/溯源用） */
    @SaCheckPermission("perf:refund:list")
    @GetMapping("/{id}")
    public R<PerfRefund> getInfo(@PathVariable Long id) {
        return R.ok(refundService.getById(id));
    }

    /** 退单汇总（冲减新签/冲减实收/笔数） */
    @SaCheckPermission("perf:refund:list")
    @GetMapping("/summary")
    public R<Map<String, Object>> summary(@RequestParam(required = false) String refundPeriod) {
        return R.ok(refundService.summary(refundPeriod));
    }

    /**
     * 退单预览：返回将冲减的新签/实收金额、明细笔数与拦截原因（blocked 非空即不可退）。
     */
    @SaCheckPermission("perf:refund:list")
    @GetMapping("/preview")
    public R<Map<String, Object>> preview(@RequestParam String contractNo,
                                          @RequestParam(required = false) String orderNo,
                                          @RequestParam String refundPeriod) {
        return R.ok(refundService.preview(contractNo, orderNo, refundPeriod));
    }

    /** 批量查合同最近退单落账月（合同列表「已退单」标记） */
    @SaCheckPermission("perf:refund:list")
    @GetMapping("/refunded-periods")
    public R<Map<String, String>> refundedPeriods(@RequestParam String contractNos) {
        List<String> keys = Arrays.stream(contractNos.split(","))
            .map(s -> s == null ? "" : s.trim()).filter(s -> !s.isEmpty()).toList();
        return R.ok(refundService.refundedPeriods(keys));
    }

    /** 发起退单并启动审批 */
    @SaCheckPermission("perf:refund:add")
    @Log(title = "退单申请", businessType = BusinessType.INSERT)
    @PostMapping
    public R<Long> add(@Validated @RequestBody PerfRefundCreateBo dto) {
        PerfRefund refund = refundService.create(dto, LoginHelper.getUserId());
        return R.ok("发起成功，已提交审批", refund.getId());
    }

    /** 申请人撤回审批中的退单（仅 SUBMITTED、仅发起人） */
    @SaCheckPermission("perf:refund:add")
    @Log(title = "退单申请", businessType = BusinessType.UPDATE)
    @PostMapping("/{id}/withdraw")
    public R<Void> withdraw(@PathVariable Long id) {
        refundService.withdraw(id, LoginHelper.getUserId());
        return R.ok("已撤回");
    }

    /** 认领当前节点待办（总监事后补赋角色） */
    @SaCheckPermission("perf:refund:list")
    @Log(title = "退单任务认领", businessType = BusinessType.UPDATE)
    @PostMapping("/{id}/claim")
    public R<Boolean> claim(@PathVariable Long id) {
        return refundService.claim(id)
            ? R.ok("认领成功", true)
            : R.fail("该退单当前无待办，或您不具备当前节点所需角色", false);
    }
}
