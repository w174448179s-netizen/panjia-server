package com.panjia.commission.handler;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.panjia.commission.domain.ApplicationStatus;
import com.panjia.commission.domain.CommissionApplication;
import com.panjia.commission.mapper.CommissionApplicationMapper;
import com.panjia.commission.service.CommissionApplicationService;
import com.panjia.commission.service.CommissionReverseService;
import com.panjia.contracts.event.DomainEventHandler;
import com.panjia.contracts.event.PerformanceFactCreatedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

/**
 * 业绩事实创建事件处理器（DomainEventHandler，消费 panjia-performance 事件）。
 * <p>
 * 处理逻辑（结佣域详细设计 §4.1 ① / §7.1，V4.2 §9.3 主路径）：
 * <ul>
 *   <li>仅 {@code PERF_REAL} 触发；PERF_EXPECT 忽略（新签透传不走结佣明细）；</li>
 *   <li>按 (period, deptId) 分组，逐个门店判定：
 *     <ul>
 *       <li>无未完结申请单 → <b>自动生成 DRAFT 申请单并拉取明细</b>（apply）；</li>
 *       <li>已有 DRAFT/SUBMITTED 单 → <b>自动增量重拉</b>追加新事实（refresh）；</li>
 *       <li>已有 APPROVED/LOCKED 单 → 不新建，标记「待补事实」提示算薪人员走调整单；</li>
 *     </ul>
 *   </li>
 *   <li>人工入口（POST /commission/apply）保留，逻辑与自动建单完全等价（幂等）。</li>
 * </ul>
 * 各门店独立 try/catch，单店失败（如封账、无可入账事实）不阻断同批其他门店建单。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PerformanceFactCreatedHandler implements DomainEventHandler {

    /** 结佣口径：仅实收业绩触发 */
    private static final String FACT_TYPE_REAL = "PERF_REAL";

    /** 系统操作人 ID（事件消费无 HTTP 上下文，参照 panjia-people 约定） */
    private static final Long SYSTEM_OPERATOR_ID = 0L;

    private final CommissionApplicationMapper applicationMapper;
    private final CommissionApplicationService applicationService;
    private final CommissionReverseService reverseService;
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

        // PERF_EXPECT 忽略（新签透传不生成明细）
        if (!FACT_TYPE_REAL.equals(event.getFactType())) {
            log.info("[结佣-事实创建] 非实收口径事实，忽略：eventId={}, factType={}", eventId, event.getFactType());
            return;
        }

        String period = event.getPeriod();
        List<String> deptIds = event.getDeptIds() == null ? List.of() : event.getDeptIds();
        StringBuilder tip = new StringBuilder();

        for (String deptIdStr : deptIds) {
            Long deptId;
            try {
                deptId = Long.valueOf(deptIdStr);
            } catch (NumberFormatException e) {
                continue;
            }
            try {
                processDept(eventId, period, deptId, tip);
            } catch (Exception e) {
                // 单店失败不阻断同批其他门店；封账 / 0 值事实等属于业务可预期情况
                String msg = "期间 " + period + " 门店 " + deptId + " 自动结佣处理失败：" + e.getMessage();
                tip.append(msg).append("；");
                log.warn("[结佣-事实创建] {}", msg, e);
            }
        }

        if (tip.isEmpty()) {
            log.info("[结佣-事实创建] 全部门店已自动处理完毕：eventId={}, period={}, deptCount={}",
                eventId, period, deptIds.size());
        }

        reverseService.recordFactCreated(eventId, period,
            event.getFactIds() == null ? List.of() : event.getFactIds(),
            tip.isEmpty() ? "已自动建单/增量重拉" : tip.toString());
    }

    /**
     * 单门店自动结佣处理（结佣域详细设计 §4.1 ①）。
     *
     * @param eventId 事件 ID（仅日志）
     * @param period  业绩归属月
     * @param deptId  门店 ID
     * @param tip     提示信息累加器（调用方负责拼接）
     */
    private void processDept(String eventId, String period, Long deptId, StringBuilder tip) {
        CommissionApplication active = applicationMapper.selectOne(
            new LambdaQueryWrapper<CommissionApplication>()
                .eq(CommissionApplication::getPeriod, period)
                .eq(CommissionApplication::getDeptId, deptId)
                .in(CommissionApplication::getStatus,
                    ApplicationStatus.DRAFT, ApplicationStatus.SUBMITTED,
                    ApplicationStatus.APPROVED, ApplicationStatus.LOCKED)
                .orderByDesc(CommissionApplication::getCreateTime)
                .last("LIMIT 1"));

        if (active == null) {
            // ① 无未完结申请单 → 自动生成 DRAFT 申请单并拉取明细
            CommissionApplication app = applicationService.apply(period, deptId, SYSTEM_OPERATOR_ID);
            String msg = "期间 " + period + " 门店 " + deptId
                + " 已自动生成结佣申请单（" + app.getApplyNo()
                + "，明细 " + app.getItemCount() + " 条，合计 " + app.getTotalAmount() + "）";
            tip.append(msg).append("；");
            log.info("[结佣-事实创建] {}", msg);
            return;
        }

        ApplicationStatus status = active.getStatus();
        if (status == ApplicationStatus.DRAFT || status == ApplicationStatus.SUBMITTED) {
            // ② 已有 DRAFT/SUBMITTED 单 → 自动增量重拉追加新事实
            applicationService.refresh(active.getId(), SYSTEM_OPERATOR_ID);
            String msg = "期间 " + period + " 门店 " + deptId
                + " 申请单（" + active.getApplyNo() + "）已自动增量重拉";
            tip.append(msg).append("；");
            log.info("[结佣-事实创建] {}", msg);
            return;
        }

        // ③ 已有 APPROVED/LOCKED 单 → 不新建，标记「待补事实」提示
        String msg = "期间 " + period + " 门店 " + deptId
            + " 存在已审批/已锁定申请单（" + active.getApplyNo()
            + "），新增事实暂不入结佣，请走差额调整（DIFF）补发";
        tip.append(msg).append("；");
        log.info("[结佣-事实创建] {}", msg);
    }
}
