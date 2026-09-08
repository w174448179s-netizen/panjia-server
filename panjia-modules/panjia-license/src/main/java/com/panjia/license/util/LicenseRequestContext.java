package com.panjia.license.util;

import java.util.UUID;

/**
 * License 调用链路 requestId 工具类（ThreadLocal）。
 *
 * 设计目标（P2-4）：
 * - LicenseServiceImpl 每次方法入口生成一个 UUID 作为本端调用链路的 requestId。
 * - 所有内部日志自动带上 [req=xxx] 前缀。
 * - 跨服务串联通过 HTTP header X-Request-Id 由调用方按需补充（当前版本未做）。
 *
 * ThreadLocal 选择：
 * - 同步方法体内安全。
 * - 后台心跳线程（HeartbeatScheduler）每次调用进入时新生成一个 UUID，独立隔离。
 * - 不需要显式清理：每次方法入口都会覆盖 setRequestId()。
 *
 * 不放进 MDC：
 * - License 模块可能作为独立 jar 嵌入到上层应用，避免污染上层日志格式。
 * - 业务侧若想对接 ELK / Trace 系统，可在此处加 MDC.put("req", ...) 一行。
 */
public final class LicenseRequestContext {

    private static final ThreadLocal<String> REQUEST_ID = new ThreadLocal<>();

    private LicenseRequestContext() {
    }

    /**
     * 在当前线程设置 requestId（覆盖式）。
     * <p>
     * ★ S-6 修复：消毒控制字符（CR/LF/TAB 及其他 C0 控制符），
     * 防止外部输入通过 [req={}] 前缀伪造日志行干扰审计。
     */
    public static void setRequestId(String requestId) {
        if (requestId == null || requestId.isEmpty()) {
            REQUEST_ID.remove();
        } else {
            REQUEST_ID.set(sanitize(requestId));
        }
    }

    /**
     * 获取当前线程的 requestId，未设置时生成一个新的 UUID。
     * 用于日志格式化的统一入口。
     */
    public static String currentRequestId() {
        String id = REQUEST_ID.get();
        if (id == null || id.isEmpty()) {
            id = generate();
            REQUEST_ID.set(id);
        }
        return id;
    }

    /**
     * 生成一个新的 requestId 并设置到当前线程。供 LicenseServiceImpl 方法入口调用。
     */
    public static String generateAndSet() {
        String id = generate();
        REQUEST_ID.set(id);
        return id;
    }

    /**
     * 清理当前线程的 requestId。
     * 当前实现下方法入口会重新生成，理论上不必清理；保留以备未来扩展。
     */
    public static void clear() {
        REQUEST_ID.remove();
    }

    private static String generate() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    /**
     * 去除控制字符（含 CR/LF/TAB），并截断超长输入。
     * 日志注入防护：换行符可伪造新日志行，超长输入可撑爆日志行。
     */
    private static String sanitize(String raw) {
        String cleaned = raw.replaceAll("[\\p{Cntrl}]", "");
        return cleaned.length() > 64 ? cleaned.substring(0, 64) : cleaned;
    }
}
