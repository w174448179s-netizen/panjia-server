package com.panjia.payroll.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.panjia.payroll.domain.PayrollAdjust;
import com.panjia.payroll.service.PayrollAdjustService;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.PageResult;
import org.dromara.common.core.domain.R;
import org.dromara.common.log.annotation.Log;
import org.dromara.common.log.enums.BusinessType;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.satoken.utils.LoginHelper;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 调整与补发：财务登记员工薪资调整（正补发/负扣回），登记即生效（APPROVED），
 * 目标期间算薪时纳入计算后置 EXECUTED。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/payroll/adjust")
public class PayrollAdjustController {

    private final PayrollAdjustService adjustService;

    /** 分页列表（期间/类型/状态/员工筛选） */
    @SaCheckPermission("payroll:adjust:list")
    @GetMapping("/list")
    public R<PageResult<PayrollAdjust>> list(@RequestParam(required = false) String period,
                                             @RequestParam(required = false) String adjustType,
                                             @RequestParam(required = false) String status,
                                             @RequestParam(required = false) Long employeeId,
                                             PageQuery pageQuery) {
        return R.ok(adjustService.pageList(period, adjustType, status, employeeId, pageQuery));
    }

    /** 登记调整/补发单 */
    @SaCheckPermission("payroll:adjust:list")
    @Log(title = "薪资调整补发", businessType = BusinessType.INSERT)
    @PostMapping
    public R<Long> create(@RequestBody PayrollAdjust item) {
        return R.ok(adjustService.create(item, LoginHelper.getUserId()));
    }

    /** 调整单详情 */
    @SaCheckPermission("payroll:adjust:list")
    @GetMapping("/{id}")
    public R<PayrollAdjust> getInfo(@PathVariable Long id) {
        return R.ok(adjustService.getById(id));
    }
}
