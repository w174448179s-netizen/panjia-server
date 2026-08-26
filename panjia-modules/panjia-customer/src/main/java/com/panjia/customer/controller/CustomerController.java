package com.panjia.customer.controller;

import com.panjia.common.annotation.AuditLog;
import org.dromara.common.core.domain.R;
import org.springframework.web.bind.annotation.*;

/**
 * 客户与版本管理 Controller（内控端）
 */
@RestController
@RequestMapping("/api/v1/customer")
public class CustomerController {

    /**
     * License 签发
     */
    @PostMapping("/license/grant")
    @AuditLog(value = AuditLog.OperateType.LICENSE_GRANT, description = "License 签发", recordParam = true)
    public R<Void> grantLicense(@RequestBody Object dto) {
        return R.ok("License 已签发");
    }

    /**
     * License 吊销
     */
    @PostMapping("/license/revoke/{customerId}")
    @AuditLog(value = AuditLog.OperateType.LICENSE_REVOKE, description = "License 吊销")
    public R<Void> revokeLicense(@PathVariable String customerId) {
        return R.ok("License 已吊销: " + customerId);
    }
}
