package com.panjia.commission.handler;

import com.panjia.commission.domain.CommissionApplication;
import com.panjia.commission.service.CommissionApplicationService;
import com.panjia.contracts.event.DomainEventHandler;
import com.panjia.contracts.event.ReceivedApprovedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.StringUtils;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * 实收审批通过事件处理器（结佣域，2026-10-29 改定）。
 * <p>
 * 触发：实收审批单变为 APPROVED（系统自动审批通过、人工审批工作流终审通过均触发）：
 * <ul>
 *   <li>业务类型为「二手买卖」→ 跳过，不自动发起结佣；</li>
 *   <li>其余类型 → 按<b>实收月</b>（事件 period，即发起月）自动发起并直提审批流。</li>
 * </ul>
 * 批次撤销/恢复的补偿性重放（{@link ReceivedApprovedEvent#isReplay()}=true）不自动发起，避免重复建单。
 * <p>
 * 留痕：发起成功后在实收审批单回填结佣申请单号（同一事务），
 * 实收审批列表/详情提示「已自动发起结佣」。
 * <p>
 * 幂等：同合同同期间已有活跃结佣单时建单抛 ServiceException，此处吞掉跳过
 * （不重试，避免重复建单）；其它业务拒绝（封账/无新签/实收未审批等）同样跳过。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReceivedApplyApprovedHandler implements DomainEventHandler {

    /** 不自动发起结佣的业务类型：二手买卖 */
    private static final String BIZ_TYPE_SECOND_HAND = "二手买卖";

    private final CommissionApplicationService applicationService;
    private final ObjectMapper objectMapper;

    @Override
    public String eventType() {
        return ReceivedApprovedEvent.EVENT_TYPE;
    }

    @Override
    public void handle(String eventId, String payloadJson) {
        ReceivedApprovedEvent event;
        try {
            event = objectMapper.readValue(payloadJson, ReceivedApprovedEvent.class);
        } catch (JacksonException e) {
            log.error("[结佣-实收通过] 事件反序列化失败：eventId={}", eventId, e);
            return;
        }
        if (event.isReplay()) {
            // 批次撤销/恢复的事件重放：仅留痕，不自动建结佣单
            log.info("[结佣-实收通过] 撤销重放事件，不自动发起结佣：eventId={}, applyId={}",
                eventId, event.getApplyId());
            return;
        }
        if (BIZ_TYPE_SECOND_HAND.equals(StringUtils.trim(event.getBizType()))) {
            log.info("[结佣-实收通过] 二手买卖不自动发起结佣：eventId={}, applyId={}, period={}, contractNo={}",
                eventId, event.getApplyId(), event.getPeriod(), event.getContractNo());
            return;
        }
        String period = StringUtils.trimToNull(event.getPeriod());
        // 合同号可空（一手房/家装荐客等无合同号业务，以订单号为业务锚点）
        String contractNo = StringUtils.trimToNull(event.getContractNo());
        String orderNo = StringUtils.trimToNull(event.getOrderNo());
        if (period == null || (contractNo == null && orderNo == null)) {
            log.warn("[结佣-实收通过] 事件缺少实收月/合同号/订单号，跳过：eventId={}, period={}, contractNo={}, orderNo={}",
                eventId, period, contractNo, orderNo);
            return;
        }
        Long operatorId = event.getOperatorId() != null ? event.getOperatorId() : 1L;
        try {
            // 发起月 = 实收月：6 月实收 6 月发起结佣
            CommissionApplication application = applicationService.applyAutoOnReceivedApproved(
                period, contractNo, orderNo, event.getApplyId(), operatorId);
            log.info("[结佣-实收通过] 已自动发起结佣：applyNo={}, period={}, contractNo={}, orderNo={}, "
                    + "itemCount={}, amount={}, receivedApplyId={}",
                application.getApplyNo(), period, application.getContractNo(), application.getOrderNo(),
                application.getItemCount(), application.getTotalAmount(), event.getApplyId());
        } catch (ServiceException e) {
            // 业务拒绝（已有活跃单/封账/无新签/实收未通过等）：跳过不重试，人工发起兜底
            log.info("[结佣-实收通过] 自动发起跳过：eventId={}, period={}, contractNo={}, 原因={}",
                eventId, period, contractNo, e.getMessage());
        }
    }
}
