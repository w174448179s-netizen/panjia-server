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
 * 实收审批通过事件处理器（2026-09-27 定稿）：按合同自动产生结佣记录。
 * <p>
 * 触发：panjia-performance 实收审批单 APPROVED（贝壳自动通过直建 / 人工审批工作流 finish）。
 * 处理：复用 {@link CommissionApplicationService#createApplicationWithItems} 构建
 * DRAFT 申请单 + 明细（明细源 = 该合同跨月 ACTIVE 新签事实逐行，金额 = 调整后值），
 * <b>不自动提交</b>——审批链保留（店长/财务人工提交后走总监审批）。
 * <p>
 * 幂等：同合同同期间已有活跃结佣单（DRAFT/SUBMITTED/APPROVED/LOCKED）时建单抛
 * ServiceException，此处吞掉跳过（人工发起的单优先，事件不重试）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReceivedApplyApprovedHandler implements DomainEventHandler {

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
        String period = event.getPeriod();
        // 合同号兜底订单号：实收行仅订单号时按订单号尝试（内部按事实真实合同号归一化）
        String contractNo = StringUtils.isNotBlank(event.getContractNo())
            ? event.getContractNo()
            : event.getOrderNo();
        if (StringUtils.isBlank(period) || StringUtils.isBlank(contractNo)) {
            log.warn("[结佣-实收通过] 事件缺少期间/合同号，跳过：eventId={}, period={}, contractNo={}",
                eventId, period, contractNo);
            return;
        }
        try {
            CommissionApplication application = applicationService.createApplicationWithItems(
                period, contractNo, event.getOperatorId(), null);
            log.info("[结佣-实收通过] 自动产生结佣记录（DRAFT 待提交）：applyNo={}, period={}, contractNo={}, "
                    + "itemCount={}, amount={}, receivedApplyId={}",
                application.getApplyNo(), period, contractNo,
                application.getItemCount(), application.getTotalAmount(), event.getApplyId());
        } catch (ServiceException e) {
            // 业务拒绝（已有活跃单幂等冲突 / 新签业绩缺失等）：跳过不重试，人工发起兜底
            log.info("[结佣-实收通过] 自动建单跳过：eventId={}, period={}, contractNo={}, 原因={}",
                eventId, period, contractNo, e.getMessage());
        }
    }
}
