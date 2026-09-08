package com.panjia.common.port;

/**
 * 审计日志记录端口（横切能力 Port）。
 * <p>
 * 业务代码只依赖此接口，不耦合 RuoYi 操作日志实现。
 * <p>
 * 状态：✅ 骨架——Adapter 规约已定，可编译。
 * <p>
 * 操作人规约：
 * <ul>
 *   <li>HTTP 上下文：Adapter 兜底解析 LoginHelper.getLoginUser()，未登录兜底 "system"</li>
 *   <li>异步 / SnailJob 场景无 HTTP 上下文：调用方必须显式传 operatorName（如 "system"）</li>
 * </ul>
 */
public interface AuditPort {

    /**
     * 记录业务审计日志。
     *
     * @param action        操作动作标识（方法名 / 业务动作）
     * @param businessKey   业务主键
     * @param operatorName   操作人名称（异步场景必须显式传，禁止从 ThreadLocal 盲取）
     */
    void record(String action, Object businessKey, String operatorName);
}
