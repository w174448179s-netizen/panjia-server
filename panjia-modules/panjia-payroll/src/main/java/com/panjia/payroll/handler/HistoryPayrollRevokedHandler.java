package com.panjia.payroll.handler;

import com.panjia.contracts.event.DomainEventHandler;
import com.panjia.contracts.event.ImportBatchRevokedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.json.JsonMapper;

import java.util.Map;

/**
 * 历史工资导入撤销处理器（HISTORY_PAYROLL 批次）。
 * <p>
 * 导入域 {@code revoke} 发布 {@link ImportBatchRevokedEvent} 后：
 * 业绩域 Handler 已按 batch_id 硬删 pj_perf_fact / pj_perf_received_apply，
 * 归一化记录与问题清单由导入域自删；本 Handler 负责薪酬域/人事域/结佣域的
 * 导入段数据清理。判据全部来自导入器写入的标记，杜绝误删业务数据：
 * <ul>
 *   <li>结佣明细/申请单：period + LOCKED + 无流程实例（导入建单不发起流程）</li>
 *   <li>工资明细/批次：按 period</li>
 *   <li>算薪事实：change_field='HIST_IMPORT' + 当月区间</li>
 *   <li>考勤/积分：data_source='IMPORT'</li>
 *   <li>考勤/积分审批单：period + APPROVED + 无流程实例</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HistoryPayrollRevokedHandler implements DomainEventHandler {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final JdbcTemplate jdbc;

    @Override
    public String eventType() {
        return ImportBatchRevokedEvent.EVENT_TYPE;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void handle(String eventId, String payloadJson) {
        Map<String, Object> payload = JSON.readValue(payloadJson, new tools.jackson.core.type.TypeReference<>() {
        });
        if (!"HISTORY_PAYROLL".equals(String.valueOf(payload.get("sourceType")))) {
            return;
        }
        String period = String.valueOf(payload.get("period"));
        log.info("[历史工资撤销] 开始清理：period={}", period);

        // ① 结佣明细 + LOCKED 申请单（导入建单无流程实例，直接硬删）
        int items = jdbc.update(
            "DELETE FROM pj_commission_item WHERE application_id IN ("
                + "SELECT id FROM pj_commission_application WHERE period = ? "
                + "AND status = 'LOCKED' AND process_instance_id IS NULL)", period);
        int apps = jdbc.update(
            "DELETE FROM pj_commission_application WHERE period = ? "
                + "AND status = 'LOCKED' AND process_instance_id IS NULL", period);
        // ② 工资明细 + 批次
        int details = jdbc.update(
            "DELETE FROM pj_payroll_detail WHERE batch_id IN ("
                + "SELECT id FROM pj_payroll_batch WHERE period = ?)", period);
        int batches = jdbc.update("DELETE FROM pj_payroll_batch WHERE period = ?", period);
        // ③ 算薪事实（导入标记，当月区间）
        int salaryFacts = jdbc.update(
            "DELETE FROM pj_people_salary_fact WHERE change_field = 'HIST_IMPORT' "
                + "AND effective_date >= ?::date AND effective_date < (?::date + INTERVAL '1 month')",
            period + "-01", period + "-01");
        // ④ 考勤 / 积分（导入来源）
        int attendance = jdbc.update(
            "DELETE FROM pj_people_attendance WHERE attend_month = ?::date AND data_source = 'IMPORT'",
            period + "-01");
        int scores = jdbc.update(
            "DELETE FROM pj_people_performance_score WHERE score_month = ?::date AND data_source = 'IMPORT'",
            period + "-01");
        // ⑤ 考勤/积分审批单（导入补齐：APPROVED 且无流程实例）
        int attApprovals = jdbc.update(
            "DELETE FROM pj_people_attendance_approval WHERE period = ? "
                + "AND status = 'APPROVED' AND process_instance_id IS NULL", period);
        int scoreApprovals = jdbc.update(
            "DELETE FROM pj_people_score_approval WHERE period = ? "
                + "AND status = 'APPROVED' AND process_instance_id IS NULL", period);

        log.info("[历史工资撤销] 清理完成：period={}，结佣明细={}, 申请单={}, 工资明细={}, 批次={}, "
                + "算薪事实={}, 考勤={}, 积分={}, 考勤审批单={}, 积分审批单={}",
            period, items, apps, details, batches, salaryFacts, attendance, scores, attApprovals, scoreApprovals);
    }
}
