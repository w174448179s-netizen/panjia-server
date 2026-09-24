package com.panjia.payroll.adapter;

import com.panjia.contracts.port.HistoryPayrollImportPort;
import com.panjia.payroll.tools.HistoryPayrollImporter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 历史工资导入适配器：导入域引擎（HISTORY_PAYROLL 旁路）经 contracts 端口
 * 委托薪酬域 {@link HistoryPayrollImporter} 执行多 sheet 导入。
 * 告警清单透传给引擎回填批次问题清单；导入失败（success=false）由引擎抛出摘要置批次 FAILED。
 */
@Component
@RequiredArgsConstructor
public class HistoryPayrollImportAdapter implements HistoryPayrollImportPort {

    private final HistoryPayrollImporter historyPayrollImporter;

    @Override
    public HistoryPayrollImportResult importAll(long importBatchId, String period, byte[] content, String fileName) {
        List<String> warnings = new ArrayList<>();
        HistoryPayrollImporter.ImportOutcome outcome = historyPayrollImporter.importFromStream(
            period, new ByteArrayInputStream(content), importBatchId, warnings);
        return new HistoryPayrollImportResult(outcome.summary(), outcome.warnings(), outcome.success());
    }
}
