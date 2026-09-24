package com.panjia.commission.handler;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.panjia.commission.domain.ApplicationStatus;
import com.panjia.commission.domain.CommissionApplication;
import com.panjia.commission.domain.CommissionItem;
import com.panjia.commission.mapper.CommissionApplicationMapper;
import com.panjia.commission.mapper.CommissionItemMapper;
import com.panjia.contracts.event.DomainEventHandler;
import com.panjia.contracts.event.ImportBatchRevokedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.utils.StringUtils;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

/**
 * 历史工资导入撤销处理器（结佣域段）。
 * <p>
 * HISTORY_PAYROLL 批次撤销时清理结佣域数据（语义同老导入器撤销清理第①段）：
 * 删除期间内导入产生的 LOCKED 申请单（无流程实例）及其全部明细。
 * 有流程实例的正常申请单不受影响。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CommissionRevokedHandler implements DomainEventHandler {

    private final CommissionApplicationMapper applicationMapper;
    private final CommissionItemMapper itemMapper;
    private final ObjectMapper objectMapper;

    @Override
    public String eventType() {
        return ImportBatchRevokedEvent.EVENT_TYPE;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void handle(String eventId, String payloadJson) {
        ImportBatchRevokedEvent event;
        try {
            event = objectMapper.readValue(payloadJson, ImportBatchRevokedEvent.class);
        } catch (JacksonException e) {
            log.error("[结佣-历史撤销] 撤销事件反序列化失败：eventId={}", eventId, e);
            return;
        }
        if (!"HISTORY_PAYROLL".equals(event.getSourceType()) || StringUtils.isBlank(event.getPeriod())) {
            return;
        }
        String period = event.getPeriod().trim();

        List<CommissionApplication> apps = applicationMapper.selectList(
            new LambdaQueryWrapper<CommissionApplication>()
                .eq(CommissionApplication::getPeriod, period)
                .eq(CommissionApplication::getStatus, ApplicationStatus.LOCKED)
                .isNull(CommissionApplication::getProcessInstanceId));
        int items = 0;
        for (CommissionApplication app : apps) {
            items += itemMapper.delete(new LambdaQueryWrapper<CommissionItem>()
                .eq(CommissionItem::getApplicationId, app.getId()));
            applicationMapper.deleteById(app.getId());
        }
        log.info("[结佣-历史撤销] 清理完成：period={}, 申请单={}, 明细={}", period, apps.size(), items);
    }
}
