package com.panjia.license.controller;

import com.panjia.license.enums.OperationEnum;
import com.panjia.license.service.LicenseService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.domain.R;
import org.springframework.web.bind.annotation.*;

/**
 * License 客户端控制器。
 * 客户端（盘家智管后端）调用此接口与授权服务器通信。
 *
 * 接口路径：/api/v1/license/*
 * 对应设计文档 §2.1 授权服务器接口。
 */
@Slf4j
@RequiredArgsConstructor
@RestController
@RequestMapping("/api/v1/license")
public class LicenseController {

    private final LicenseService licenseService;

    /**
     * 首次激活，绑定指纹，校验版本范围
     */
    @PostMapping("/activate")
    public R<LicenseService.ActivateResult> activate(@RequestParam String authCode,
                                                     @RequestParam String hostMachineId,
                                                     @RequestParam String instanceId,
                                                     @RequestParam String productVersion) {
        var fp = com.panjia.license.domain.HardwareFingerprint.builder()
                .hostMachineId(hostMachineId)
                .instanceId(instanceId)
                .build();
        var result = licenseService.activate(authCode, fp, productVersion);
        return R.ok(result);
    }

    /**
     * 24h 心跳续期，可下发 clientMode 指令
     */
    @PostMapping("/heartbeat")
    public R<LicenseService.HeartbeatResult> heartbeat() {
        var result = licenseService.heartbeat();
        return R.ok(result);
    }

    /**
     * 关键操作实时校验，按 operation 枚举返回
     */
    @PostMapping("/check")
    public R<LicenseService.CheckResult> check(@RequestParam String operation) {
        var result = licenseService.check(operation);
        // 允许/拒绝都返回 200，由业务层判断 result.isAllowed()
        return R.ok(result);
    }

    /**
     * 查询当前操作是否允许（供前端按钮态使用）
     */
    @GetMapping("/check/{operation}")
    public R<Boolean> isAllowed(@PathVariable OperationEnum operation) {
        return R.ok(licenseService.isOperationAllowed(operation.name()));
    }
}
