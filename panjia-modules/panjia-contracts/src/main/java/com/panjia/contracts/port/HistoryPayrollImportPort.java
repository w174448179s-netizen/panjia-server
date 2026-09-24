package com.panjia.contracts.port;

import java.util.List;

/**
 * 历史工资导入端口（导入域 → 薪酬域）。
 * <p>
 * 历史工资 Excel（天街工资表，7 个 sheet）不走导入域标准的 raw→归一化 管线，
 * 而是由薪酬域 {@code HistoryPayrollImporter} 跨域直写工资/考勤/积分/算薪事实/
 * 业绩事实/审批单等表。导入域引擎对 {@code HISTORY_PAYROLL} 来源走旁路：
 * 归档原文件 + 建批次后委托本端口执行，告警行回填为批次问题清单。
 *
 * @see com.panjia.contracts.event.ImportBatchRevokedEvent 撤销导入级联（薪酬域 HistoryPayrollRevokedHandler）
 */
public interface HistoryPayrollImportPort {

    /**
     * 执行历史工资全量导入（分段幂等：工资/考勤/积分/算薪事实/业绩/审批单各段独立判断）。
     *
     * @param importBatchId 导入域批次 ID（业绩事实/归一化记录/实收审批单挂该批次，撤销时级联）
     * @param period        工资归属月（YYYY-MM）
     * @param content       xlsx 文件字节
     * @param fileName      原始文件名（仅日志）
     * @return 摘要 + 告警清单 + 是否成功（失败时引擎将批次置 FAILED 并抛出摘要信息）
     */
    HistoryPayrollImportResult importAll(long importBatchId, String period, byte[] content, String fileName);

    /**
     * @param summary  导入结果摘要（完成/失败原因）
     * @param warnings 告警清单（员工未匹配、日期解析失败等跳过行，逐条入批次问题清单）
     * @param success  是否成功
     */
    record HistoryPayrollImportResult(String summary, List<String> warnings, boolean success) {
    }
}
