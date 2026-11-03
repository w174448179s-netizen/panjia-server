package com.panjia.ledger.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.panjia.ledger.dto.LedgerReportVO;
import com.panjia.ledger.service.LedgerReportService;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 部门收支分析报表。
 */
@RestController
@RequestMapping("/ledger/report")
@RequiredArgsConstructor
public class LedgerReportController {

    private final LedgerReportService reportService;

    @SaCheckPermission("ledger:dept:list")
    @GetMapping
    public R<LedgerReportVO> report(@RequestParam String period) {
        return R.ok(reportService.report(period));
    }
}
