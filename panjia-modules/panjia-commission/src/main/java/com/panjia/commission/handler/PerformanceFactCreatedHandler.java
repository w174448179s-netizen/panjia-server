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

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 业绩事实创建事件处理器（DomainEventHandler，消费 panjia-performance 事件）。
 * <p>
 * 双向联动实收自动审批（2026-09-30 定稿）：
 * <ul>
 *   <li>{@code PERF_REAL}（实收导入产生事实）：仅留痕，<b>不建单</b>——实收导入时尚未审批，
 *       结佣须待实收审批通过（APPROVED）后由业务人员手工发起；</li>
 *   <li>{@code PERF_EXPECT}（新签导入产生事实）：<b>触发实收审批通过</b>——查同业务键跨月 DRAFT
 *       实收事实（实收月 ≥ 新签月），对每个业务键/实收月调
 *       {@link ReceivedApplyPort#resolveDraftAfterNewSign} 重新评估：实收 ≥ 新签→实收单自动通过；
 *       实收 &lt; 新签→启动人工审批。覆盖「实收先导入（无新签→DRAFT）、新签后导入」场景。
 *       结佣单由业务人员在结佣列表对 APPROVED 实收手工发起（§3.2 前置），本处理器不建结佣单。</li>
 * </ul>
 * 幂等：resolveDraftAfterNewSign 仅处理 DRAFT 单（SUBMITTED/APPROVED 不打扰），
 * 重复事件无副作用；此处 catch ServiceException 跳过不重试。
 * <p>
 * 硬约束：无新签不自动通过——实收导入时无新签即建 DRAFT 不启动工作流，等新签导入后才评估。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PerformanceFactCreatedHandler implements DomainEventHandler {

    /** 结佣口径：实收业绩事实 */
    private static final String FACT_TYPE_REAL = "PERF_REAL";
    /** 新签业绩事实口径 */
    private static final String FACT_TYPE_EXPECT = "PERF_EXPECT";
    /** 实收审批单 DRAFT 状态：未启动工作流，等待新签触发评估 */
    private static final String RECEIVED_STATUS_DRAFT = "DRAFT";

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
            // 新签导入：触发同合同 DRAFT 实收审批单重新评估（覆盖则自动通过→建结佣单）
            resolveReceivedAfterNewSign(event, eventId);
            return;
        }

        log.info("[结佣-事实创建] 非新签/实收口径，忽略：eventId={}, factType={}", eventId, event.getFactType());
    }

    /**
     * 新签导入触发：查同业务键跨月 DRAFT 实收事实，对每个匹配实收月触发 received 域重新评估。
     * <p>
     * 匹配规则：实收月 >= 新签月（新签月之前的实收不属于此新签）+ receivedStatus=DRAFT
     * + amount!=0。对每个业务键/实收月调
     * {@link ReceivedApplyPort#resolveDraftAfterNewSign}：实收 ≥ 新签→实收单自动通过；
     * 实收 &lt; 新签→启动人工审批。ServiceException 跳过不重试（重复事件无副作用）。
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

        // 2. 跨月查同业务键所有 PERF_REAL 事实
        List<PerformanceFactSummaryDTO> realFacts = performanceQueryPort.findActiveByBizKeys(bizKeys, FACT_TYPE_REAL);
        if (realFacts.isEmpty()) {
            log.info("[结佣-新签触发] 同业务键无实收事实，跳过：eventId={}, period={}, bizKeys={}",
                eventId, newSignPeriod, bizKeys);
            return;
        }

        // 3. 按 (订单号, 实收月) 分组，过滤 DRAFT + amount!=0 + 实收月>=新签月。
        //    订单号为唯一业务锚点：按订单号分组逐单评估，同合同多订单互不串单；
        //    组内合同号取首个非空快照（可空），仅供 received 域历史单据（order_no 空）回退查询用。
        //    防御：实收表 order_no 为空的行按合同号回退分组（实收表 order_no 非空，理论不达）。
        Map<String, Set<String>> orderToRealPeriods = new LinkedHashMap<>();
        Map<String, String> contractNoByOrder = new HashMap<>();
        Map<String, Set<String>> contractFallbackToRealPeriods = new LinkedHashMap<>();
        for (PerformanceFactSummaryDTO f : realFacts) {
            if (f.getAmount() == null || f.getAmount().compareTo(BigDecimal.ZERO) == 0) {
                continue;
            }
            if (!RECEIVED_STATUS_DRAFT.equals(f.getReceivedStatus())) {
                continue;
            }
            if (f.getPeriod() == null || f.getPeriod().compareTo(newSignPeriod) < 0) {
                continue;
            }
            if (StringUtils.isNotBlank(f.getOrderNo())) {
                orderToRealPeriods.computeIfAbsent(f.getOrderNo(), k -> new LinkedHashSet<>()).add(f.getPeriod());
                contractNoByOrder.putIfAbsent(f.getOrderNo(), StringUtils.isNotBlank(f.getContractNo())
                    ? f.getContractNo() : null);
            } else if (StringUtils.isNotBlank(f.getContractNo())) {
                contractFallbackToRealPeriods.computeIfAbsent(f.getContractNo(), k -> new LinkedHashSet<>())
                    .add(f.getPeriod());
            }
        }
        if (orderToRealPeriods.isEmpty() && contractFallbackToRealPeriods.isEmpty()) {
            log.info("[结佣-新签触发] 同业务键无 DRAFT 实收，跳过：eventId={}, period={}, bizKeys={}",
                eventId, newSignPeriod, bizKeys);
            return;
        }

        // 4. 对每个 (订单号, 实收月) 触发 received 域重新评估（通过→自动通过实收单）
        int approved = 0;
        for (Map.Entry<String, Set<String>> entry : orderToRealPeriods.entrySet()) {
            String orderNo = entry.getKey();
            String contractNo = contractNoByOrder.get(orderNo);
            for (String realPeriod : entry.getValue()) {
                try {
                    boolean pass = receivedApplyPort.resolveDraftAfterNewSign(
                        realPeriod, contractNo, orderNo, null);
                    if (pass) {
                        approved++;
                    }
                    log.info("[结佣-新签触发] 评估完成：period={}, orderNo={}, contractNo={}, approved={}, eventId={}",
                        realPeriod, orderNo, contractNo, pass, eventId);
                } catch (ServiceException e) {
                    log.info("[结佣-新签触发] 跳过：period={}, orderNo={}, contractNo={}, 原因={}, eventId={}",
                        realPeriod, orderNo, contractNo, e.getMessage(), eventId);
                }
            }
        }
        // 防御回退：无订单号的实收行按合同号评估（received 域 order_no 空才按合同号查 DRAFT）
        for (Map.Entry<String, Set<String>> entry : contractFallbackToRealPeriods.entrySet()) {
            for (String realPeriod : entry.getValue()) {
                try {
                    boolean pass = receivedApplyPort.resolveDraftAfterNewSign(realPeriod, entry.getKey(), null, null);
                    if (pass) {
                        approved++;
                    }
                } catch (ServiceException e) {
                    log.info("[结佣-新签触发] 跳过：period={}, contractNo={}, 原因={}, eventId={}",
                        realPeriod, entry.getKey(), e.getMessage(), eventId);
                }
            }
        }
        log.info("[结佣-新签触发] 完成：approved={}, eventId={}, period={}, bizKeys={}",
            approved, eventId, newSignPeriod, bizKeys);
    }
}
