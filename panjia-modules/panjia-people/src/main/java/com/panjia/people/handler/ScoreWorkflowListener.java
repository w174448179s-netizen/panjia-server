package com.panjia.people.handler;

import com.panjia.contracts.constant.BizType;
import com.panjia.contracts.event.ApprovalEvent;
import com.panjia.people.service.ScoreApprovalService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 积分月度审批工作流回调监听器（bizType = {@link BizType#SCORE_APPROVAL}）。
 * <p>
 * 实例级 {@link ApprovalEvent}（WarmFlowEventTranslator 转译）：
 * finish（总监审核节点办理完成）→ APPROVED；
 * back（总监驳回）→ REJECTED（原因取 message）；
 * cancel/invalid/termination → DRAFT。
 * <p>
 * 审批动作全部经「我的待办」由引擎按 flow_user 名单判权办理，
 * 不存在业务接口直改状态的业务直批路径。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ScoreWorkflowListener {

    private final ScoreApprovalService approvalService;

    @EventListener(condition = "#approvalEvent.bizType == '" + BizType.SCORE_APPROVAL + "'")
    public void onApprovalEvent(ApprovalEvent approvalEvent) {
        try {
            Long bizId = approvalEvent.getBizId();
            if (bizId == null) {
                log.warn("[积分工作流] bizId 为空，跳过：{}", approvalEvent);
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
            log.info("[积分工作流] 回调：bizId={}, status={}, nodeCode={}",
                bizId, approvalEvent.getStatus(), approvalEvent.getNodeCode());
            approvalService.handleWorkflowEvent(bizId, approvalEvent.getStatus(), handler, message);
        } catch (Exception e) {
            log.error("[积分工作流] 回调处理失败：{}", approvalEvent, e);
        }
    }
}
