package com.panjia.license.service;

import org.springframework.stereotype.Service;

import java.time.LocalDate;

/**
 * License 授权校验服务
 * <p>
 * 校验 License 有效性，支持三种状态：
 * <ul>
 *   <li>VALID — 授权有效，正常使用</li>
 *   <li>GRACE_PERIOD — 到期保护期，仅允许查询和导出</li>
 *   <li>EXPIRED — 完全过期，拒绝所有请求</li>
 *   <li>UNAUTHORIZED — 未授权，拒绝所有请求</li>
 * </ul>
 */
@Service
public class LicenseService {

    /**
     * License 状态
     */
    public enum LicenseStatus {
        VALID, GRACE_PERIOD, EXPIRED, UNAUTHORIZED
    }

    /**
     * 检查当前 License 状态
     *
     * @return License 状态
     */
    public LicenseStatus checkStatus() {
        // TODO: 从配置文件或 License 文件中解析实际状态
        // 当前实现返回 VALID，实际部署时需对接 License 校验逻辑
        return LicenseStatus.VALID;
    }

    /**
     * 获取到期日期
     */
    public LocalDate getExpiryDate() {
        // TODO: 从 License 文件解析
        return LocalDate.now().plusYears(1);
    }

    /**
     * 获取客户名称
     */
    public String getCustomerName() {
        // TODO: 从 License 文件解析
        return "默认客户";
    }
}
