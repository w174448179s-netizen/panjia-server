package com.panjia.payroll.handler;

import com.panjia.contracts.constant.BizType;
import com.panjia.contracts.event.ApprovalEvent;
import com.panjia.payroll.service.ISelfPurchaseService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 自购单提成审批工作流回调监听器（bizType = {@link BizType#SELF_PURCHASE}）。
 * <p>
 * 实例级 {@link ApprovalEvent}（WarmFlowEventTranslator 转译）：
 * finish（总监审核节点办理完成）→ APPROVED；
 * back（总监驳回）→ REJECTED（原因取 message）；
 * cancel/invalid/termination → DRAFT。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SelfPurchaseWorkflowListener {

    private final ISelfPurchaseService selfPurchaseService;

    @EventListener(condition = "#approvalEvent.bizType == '" + BizType.SELF_PURCHASE + "'")
    public void onApprovalEvent(ApprovalEvent approvalEvent) {
        try {
            Long bizId = approvalEvent.getBizId();
            if (bizId == null) {
                log.warn("[自购单提成工作流] bizId 为空，跳过：{}", approvalEvent);
                return;
            }
            String handler = null;
            String message = null;
            Map<String, Object> params = approvalEvent.getParams();
            if (params != null) {
                Object h = params.get("handler");
                Object m = params.get("message");
                handler = h == null ? null : h.toString();
                message = m == null ? null : m.toString();
            }
            log.info("[自购单提成工作流] 回调：bizId={}, status={}, nodeCode={}",
                bizId, approvalEvent.getStatus(), approvalEvent.getNodeCode());
            selfPurchaseService.handleWorkflowEvent(bizId, approvalEvent.getStatus(), handler, message);
        } catch (Exception e) {
            log.error("[自购单提成工作流] 回调处理失败：{}", approvalEvent, e);
        }
    }
}
