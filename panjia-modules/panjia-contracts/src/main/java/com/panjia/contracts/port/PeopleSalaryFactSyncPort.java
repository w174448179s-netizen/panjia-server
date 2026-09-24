package com.panjia.contracts.port;

import com.panjia.contracts.dto.SalaryFactSyncDTO;

import java.util.List;

/**
 * 员工域算薪事实同步端口（payroll → people）。
 * <p>
 * pj_people_salary_fact 归 people 域管辖，payroll 域（历史工资导入归档消费）
 * 经本端口写算薪事实切片，禁止直连 people 表。
 */
public interface PeopleSalaryFactSyncPort {

    /**
     * 历史工资导入：按期间同步员工算薪事实（change_field='HIST_IMPORT'）。
     * <p>
     * 实现语义（与老导入器 processSalaryFactSegment 对齐）：
     * <ul>
     *   <li>切片区间 [归属月月初, 次月初)，不影响其他月份取数；</li>
     *   <li>与该月月初时点既有事实链比对，一致则跳过（幂等）；</li>
     *   <li>该员工该类型当月已有 HIST_IMPORT 切片时跳过（重导幂等）。</li>
     * </ul>
     * 同步失败不阻断导入主流程（调用方负责捕获告警）。
     *
     * @param period 归属期间（YYYY-MM）
     * @param facts  事实列表（employeeId + factType + value）
     * @return 实际写入条数（跳过不计）
     */
    int syncHistorySalaryFacts(String period, List<SalaryFactSyncDTO> facts);

    /**
     * 历史工资导入批次撤销：删除该期间导入的算薪事实切片
     * （change_field='HIST_IMPORT' 且 effective_date ∈ [月初, 次月初)）。
     *
     * @param period 归属期间（YYYY-MM）
     * @return 删除条数
     */
    int deleteHistoryFacts(String period);
}
