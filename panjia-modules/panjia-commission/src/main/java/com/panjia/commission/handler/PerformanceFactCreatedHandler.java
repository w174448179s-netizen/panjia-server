package com.panjia.commission.handler;

import com.panjia.commission.service.CommissionReverseService;
import com.panjia.contracts.dto.PerformanceFactSummaryDTO;
import com.panjia.contracts.event.DomainEventHandler;
import com.panjia.contracts.event.PerformanceFactCreatedEvent;
import com.panjia.contracts.port.CommissionPerformanceQueryPort;
import com.panjia.contracts.port.ReceivedApplyPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.StringUtils;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 业绩事实创建事件处理器（DomainEventHandler，消费 panjia-performance 事件）。
 * <p>
 * 双向联动实收自动审批（2026-10-29 去草稿定稿）：
 * <ul>
 *   <li>{@code PERF_REAL}（实收导入产生事实）：仅留痕，<b>不建单</b>——实收导入时尚未审批，
 *       结佣须待实收审批通过（APPROVED）；系统自动通过时由实收审批通过事件自动发起结佣；</li>
 *   <li>{@code PERF_EXPECT}（新签导入产生事实）：<b>触发实收审批补建单</b>——按业务键查
 *       「明细已入库、未绑定审批单」的到账（实收月 ≥ 新签月），逐条调
 *       {@link ReceivedApplyPort#resolvePendingAfterNewSign}：实收 ≥ 新签且人员一致→自动通过；
 *       否则启动人工审批。覆盖「先导实收（无新签→仅落库）、后导新签」场景。
 *       实收自动通过后由 {@link ReceivedApplyApprovedHandler} 自动发起结佣；本处理器不建结佣单。</li>
 * </ul>
 * 幂等：待匹配到账在补建单后即被绑定，重复事件不会重复出单；
 * 此处 catch ServiceException 跳过不重试。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PerformanceFactCreatedHandler implements DomainEventHandler {

    /** 结佣口径：实收业绩事实 */
    private static final String FACT_TYPE_REAL = "PERF_REAL";
    /** 新签业绩事实口径 */
    private static final String FACT_TYPE_EXPECT = "PERF_EXPECT";

    private final CommissionReverseService reverseService;
    private final ReceivedApplyPort receivedApplyPort;
    private final CommissionPerformanceQueryPort performanceQueryPort;
    private final ObjectMapper objectMapper;

    @Override
    public String eventType() {
        return PerformanceFactCreatedEvent.EVENT_TYPE;
    }

    @Override
    public void handle(String eventId, String payloadJson) {
        PerformanceFactCreatedEvent event;
        try {
            event = objectMapper.readValue(payloadJson, PerformanceFactCreatedEvent.class);
        } catch (JacksonException e) {
            log.error("[结佣-事实创建] 事件反序列化失败：eventId={}", eventId, e);
            return;
        }

        List<String> factIds = event.getFactIds() == null ? List.of() : event.getFactIds();
        if (FACT_TYPE_REAL.equals(event.getFactType())) {
            // 实收导入产生事实：未审批，仅留痕（建单由实收审批通过事件负责）
            reverseService.recordFactCreated(eventId, event.getPeriod(), factIds,
                "实收事实已就绪，等待审批通过后由实收审批通过事件建单");
            log.info("[结佣-事实创建] 实收事实已记录，等待审批：eventId={}, period={}, factCount={}",
                eventId, event.getPeriod(), factIds.size());
            return;
        }

        if (FACT_TYPE_EXPECT.equals(event.getFactType())) {
            // 新签导入：触发同业务键「待匹配实收」补建审批单（覆盖则自动通过→建结佣单）
            resolveReceivedAfterNewSign(event, eventId);
            return;
        }

        log.info("[结佣-事实创建] 非新签/实收口径，忽略：eventId={}, factType={}", eventId, event.getFactType());
    }

    /**
     * 新签导入触发：查同业务键「已到账、未绑定审批单」的实收，对每个匹配实收月触发补建单。
     * <p>
     * 匹配规则：实收月 >= 新签月（新签月之前的实收不属于此新签）。
     * 对每个（业务键, 实收月）调 {@link ReceivedApplyPort#resolvePendingAfterNewSign}：
     * 实收 ≥ 新签且人员一致→实收单自动通过；否则启动人工审批。
     * ServiceException 跳过不重试（重复事件无副作用）。
     * 业务键口径：订单号优先（唯一业务锚点）；无订单号的按合同号。
     */
    private void resolveReceivedAfterNewSign(PerformanceFactCreatedEvent event, String eventId) {
        List<String> factIdStrs = event.getFactIds() == null ? List.of() : event.getFactIds();
        if (factIdStrs.isEmpty()) {
            return;
        }
        String newSignPeriod = event.getPeriod();
        if (StringUtils.isBlank(newSignPeriod)) {
            log.warn("[结佣-新签触发] 事件缺归属月，跳过：eventId={}", eventId);
            return;
        }

        // 1. 查本批新签事实，提取业务键：订单号优先（唯一业务锚点）；
        //    无订单号（历史/异常数据）以合同号为键，实收侧 findActiveByBizKeys 按 order_no/contract_no 双列匹配
        List<Long> factIds = factIdStrs.stream().map(Long::valueOf).toList();
        List<PerformanceFactSummaryDTO> newSignFacts = performanceQueryPort.findActiveByFacts(factIds);
        Set<String> bizKeys = newSignFacts.stream()
            .map(f -> StringUtils.isNotBlank(f.getOrderNo()) ? f.getOrderNo() : f.getContractNo())
            .filter(StringUtils::isNotBlank)
            .collect(Collectors.toCollection(LinkedHashSet::new));
        if (bizKeys.isEmpty()) {
            log.info("[结佣-新签触发] 本批新签事实无有效订单号/合同号，跳过：eventId={}, period={}", eventId, newSignPeriod);
            return;
        }

        // 2. 跨月查同业务键「已到账、未建单」的实收到账（2026-10-29 去草稿：
        //    待匹配状态以「实收明细 received_apply_id 为空」承载，不再依赖 DRAFT 单/PERE_REAL 事实）
        List<com.panjia.contracts.port.ReceivedApplyPort.PendingReceipt> pendings =
            receivedApplyPort.findPendingReceipts(bizKeys);
        if (pendings.isEmpty()) {
            log.info("[结佣-新签触发] 同业务键无待匹配实收到账，跳过：eventId={}, period={}, bizKeys={}",
                eventId, newSignPeriod, bizKeys);
            return;
        }

        // 3. 按「实收月 ≥ 新签月」过滤；订单号为唯一业务锚点，无订单号的按合同号评估
        int approved = 0;
        for (com.panjia.contracts.port.ReceivedApplyPort.PendingReceipt p : pendings) {
            if (p.period() == null || p.period().compareTo(newSignPeriod) < 0) {
                continue;
            }
            try {
                boolean pass = receivedApplyPort.resolvePendingAfterNewSign(
                    p.period(), p.contractNo(), p.orderNo(), null, event.getBatchId());
                if (pass) {
                    approved++;
                }
                log.info("[结佣-新签触发] 补建完成：period={}, orderNo={}, contractNo={}, built={}, eventId={}",
                    p.period(), p.orderNo(), p.contractNo(), pass, eventId);
            } catch (ServiceException e) {
                log.info("[结佣-新签触发] 跳过：period={}, orderNo={}, contractNo={}, 原因={}, eventId={}",
                    p.period(), p.orderNo(), p.contractNo(), e.getMessage(), eventId);
            } catch (Exception e) {
                // 唯一索引冲突等非业务异常：跳过不重试（该订单等下次触发或人工处理），
                // 不能让单条订单的异常把整个事件打成 FAILED 而反复重放
                log.warn("[结佣-新签触发] 补建单异常跳过：period={}, orderNo={}, contractNo={}, eventId={}, cause={}",
                    p.period(), p.orderNo(), p.contractNo(), eventId, e.getMessage());
            }
        }
        log.info("[结佣-新签触发] 完成：built={}, eventId={}, period={}, bizKeys={}",
            approved, eventId, newSignPeriod, bizKeys);
    }
}
