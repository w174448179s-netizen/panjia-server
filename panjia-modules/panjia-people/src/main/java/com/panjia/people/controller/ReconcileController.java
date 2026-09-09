package com.panjia.people.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.panjia.people.dto.ReconcileResult;
import com.panjia.people.service.ReconcileService;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.dromara.common.log.annotation.Log;
import org.dromara.common.log.enums.BusinessType;
import org.dromara.common.web.core.BaseController;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 员工-系统账户对账（手动触发，people 单向覆盖 sys_user）。
 */
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/people/reconcile")
public class ReconcileController extends BaseController {

    private final ReconcileService reconcileService;

    /**
     * 执行对账并自动修复差异。
     *
     * @return 对账结果
     */
    @SaCheckPermission("people:employee:reconcile")
    @Log(title = "员工对账", businessType = BusinessType.UPDATE)
    @PostMapping("/run")
    public R<ReconcileResult> run() {
        return R.ok("对账完成", reconcileService.runReconcile());
    }
}
