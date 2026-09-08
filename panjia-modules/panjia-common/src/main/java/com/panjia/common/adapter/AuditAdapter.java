package com.panjia.common.adapter;

import cn.dev33.satoken.exception.NotLoginException;
import com.panjia.common.port.AuditPort;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.log.event.OperLogEvent;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.system.api.model.LoginUser;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * 审计日志 Adapter（实现 {@link AuditPort}）。
 * <p>
 * 适配说明：任务卡伪代码用 ISysOperLogService.insertOperlog(SysOperLog)，
 * 但 panjia-common 不依赖 ruoyi-system（分层约束），且 RuoYi-Vue-Plus 6.x
 * 操作日志为事件驱动机制——通过 ApplicationEventPublisher 发布
 * {@link OperLogEvent}，由 ruoyi-system 的监听器落库（与 LogAspect 一致）。
 * <p>
 * 操作人解析规约：
 * <ul>
 *   <li>HTTP 上下文：LoginHelper.getLoginUser().getUsername()，未登录兜底 "system"</li>
 *   <li>异步 / SnailJob 场景：调用方显式传 operatorName，Adapter 优先用传入值</li>
 *   <li>resolveCurrentUser 只捕获 NotLoginException，其他异常上抛（避免真实操作人被错写成 system）</li>
 * </ul>
 */
@Slf4j
@Component
public class AuditAdapter implements AuditPort {

    /** 业务类型 99 = 盘家自定义业务操作（与 RuoYi 原生 0-5 区分） */
    private static final int BUSINESS_TYPE_PANJIA = 99;

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    @Override
    public void record(String action, Object businessKey, String operatorName) {
        // 操作人优先用传入值；为空时走 HTTP 上下文解析
        String operName = (operatorName != null && !operatorName.isBlank())
            ? operatorName
            : resolveCurrentUser();

        OperLogEvent operLog = new OperLogEvent();
        operLog.setTitle("盘家业务操作");
        operLog.setBusinessType(BUSINESS_TYPE_PANJIA);
        operLog.setMethod(action);
        operLog.setOperParam(String.valueOf(businessKey));
        operLog.setOperName(operName);
        // 异步场景无 HTTP 请求上下文，IP/URL 等字段由 LogAspect 切面在 HTTP 场景填充，
        // 手动调用时不强制要求这些字段（与 RuoYi @Log 切面行为对齐）

        eventPublisher.publishEvent(operLog);
    }

    /**
     * 解析当前登录用户名，仅未登录时兜底 "system"。
     * <p>
     * 仅捕获 NotLoginException；其他异常（如 Token 解析失败）继续上抛，
     * 避免真实操作人被错写成 system。
     *
     * @return 当前登录用户名，未登录返回 "system"
     */
    private String resolveCurrentUser() {
        try {
            LoginUser loginUser = LoginHelper.getLoginUser();
            return loginUser.getUsername();
        } catch (NotLoginException e) {
            // 仅「未登录」才兜底 system（如 SnailJob 无 HTTP 上下文场景）
            return "system";
        }
    }
}
