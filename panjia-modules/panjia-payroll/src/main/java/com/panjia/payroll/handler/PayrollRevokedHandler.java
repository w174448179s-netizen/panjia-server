package com.panjia.payroll.handler;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.panjia.contracts.event.DomainEventHandler;
import com.panjia.contracts.event.ImportBatchRevokedEvent;
import com.panjia.contracts.port.PeopleSalaryFactSyncPort;
import com.panjia.payroll.domain.PayrollBatch;
import com.panjia.payroll.domain.PayrollDetail;
import com.panjia.payroll.mapper.PayrollBatchMapper;
import com.panjia.payroll.mapper.PayrollDetailMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.utils.StringUtils;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.time.YearMonth;
import java.time.format.DateTimeParseException;

/**
 * 历史工资导入撤销处理器（薪酬域段）。
 * <p>
 * HISTORY_PAYROLL 批次撤销时清理薪酬域数据（考勤/积分/审批单由 people 域
 * RevokedHandler 清理，结佣 LOCKED 单由 commission 域清理，各自消费自己的事件）：
 * <ul>
 *   <li>工资明细 + 批次：按 period（历史补录期间无正常算薪批次）；</li>
 *   <li>算薪事实：change_field='HIST_IMPORT' + 当月区间 [月初, 次月初)。</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PayrollRevokedHandler implements DomainEventHandler {

    private final PayrollBatchMapper batchMapper;
    private final PayrollDetailMapper detailMapper;
    private final PeopleSalaryFactSyncPort salaryFactSyncPort;
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
            log.error("[历史工资撤销] 撤销事件反序列化失败：eventId={}", eventId, e);
            return;
        }
        if (!"HISTORY_PAYROLL".equals(event.getSourceType()) || StringUtils.isBlank(event.getPeriod())) {
            return;
        }
        String period = event.getPeriod().trim();
        try {
            YearMonth.parse(period);
        } catch (DateTimeParseException e) {
            log.warn("[历史工资撤销] 期间 {} 非法，跳过清理", period);
            return;
        }

        // ① 工资明细 + 批次（按期间）
        int details = detailMapper.delete(new LambdaQueryWrapper<PayrollDetail>()
            .eq(PayrollDetail::getPeriod, period));
        int batches = batchMapper.delete(new LambdaQueryWrapper<PayrollBatch>()
            .eq(PayrollBatch::getPeriod, period));
        // ② 算薪事实（导入标记 + 当月区间，走 people 域端口）
        int facts = salaryFactSyncPort.deleteHistoryFacts(period);

        log.info("[历史工资撤销] 薪酬域清理完成：period={}, 工资明细={}, 批次={}, 算薪事实={}",
            period, details, batches, facts);
    }
}
