package com.panjia.ledger.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.stp.StpUtil;
import com.panjia.ledger.domain.LfMatchResult;
import com.panjia.ledger.dto.LfAllocBo;
import com.panjia.ledger.dto.LfDiffDetailVO;
import com.panjia.ledger.dto.LfImportResultVO;
import com.panjia.ledger.dto.LfStatementExcelRow;
import com.panjia.ledger.dto.LfStatementQuery;
import com.panjia.ledger.dto.LfStatementVO;
import com.panjia.ledger.service.LfStatementImportService;
import com.panjia.ledger.service.LfStatementQueryService;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.PageResult;
import org.dromara.common.core.domain.R;
import org.dromara.common.excel.utils.ExcelBuilder;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.satoken.utils.LoginHelper;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 部门收支 · 理房通流水。
 */
@RestController
@RequestMapping("/ledger/lf")
@RequiredArgsConstructor
public class LfStatementController {

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final LfStatementImportService importService;
    private final LfStatementQueryService queryService;

    @SaCheckPermission("ledger:lf:list")
    @GetMapping("/list")
    public R<PageResult<LfStatementVO>> list(@ModelAttribute LfStatementQuery query,
                                             @ModelAttribute PageQuery pageQuery) {
        return R.ok(queryService.list(query, pageQuery, LoginHelper.getDeptId(), allScope()));
    }

    @SaCheckPermission("ledger:lf:import")
    @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public R<LfImportResultVO> importData(@RequestPart("file") MultipartFile file,
                                          @RequestParam Long storeDeptId) throws Exception {
        return R.ok(importService.submitImport(file.getBytes(), file.getOriginalFilename(), storeDeptId,
            LoginHelper.getUserId()));
    }

    @SaCheckPermission("ledger:lf:import")
    @GetMapping("/import/result")
    public R<LfImportResultVO> importResult(@RequestParam Long batchId) {
        return R.ok(importService.getImportResult(batchId));
    }

    @SaCheckPermission("ledger:lf:alloc")
    @PutMapping("/alloc")
    public R<Integer> allocate(@RequestBody LfAllocBo bo) {
        return R.ok(queryService.allocate(bo, LoginHelper.getUserId()));
    }

    @SaCheckPermission("ledger:lf:match")
    @PostMapping("/rematchSubject")
    public R<Integer> rematchSubject(@RequestParam(required = false) Long storeDeptId) {
        return R.ok(queryService.rematchPendingSubjects(storeDeptId, LoginHelper.getUserId()));
    }

    @SaCheckPermission("ledger:lf:list")
    @GetMapping("/pendingSubjects")
    public R<PageResult<java.util.Map<String, Object>>> pendingSubjects(
        @RequestParam(required = false) Long storeDeptId,
        @ModelAttribute PageQuery pageQuery) {
        return R.ok(queryService.pendingSubjects(storeDeptId, pageQuery));
    }

    @SaCheckPermission("ledger:lf:list")
    @GetMapping("/diff/list")
    public R<PageResult<LfMatchResult>> diffList(@RequestParam(required = false) String matchStatus,
                                                 @RequestParam(required = false) String contractNo,
                                                 @ModelAttribute PageQuery pageQuery) {
        return R.ok(queryService.diffList(matchStatus, contractNo, pageQuery));
    }

    @SaCheckPermission("ledger:lf:list")
    @GetMapping("/diff/detail")
    public R<LfDiffDetailVO> diffDetail(@RequestParam String contractNo) {
        return R.ok(queryService.diffDetail(contractNo));
    }

    @SaCheckPermission("ledger:lf:export")
    @PostMapping("/export")
    public void export(@ModelAttribute LfStatementQuery query, HttpServletResponse response) {
        List<LfStatementVO> list = queryService.listAll(query, LoginHelper.getDeptId(), allScope());
        List<LfStatementExcelRow> rows = new ArrayList<>(list.size());
        for (LfStatementVO vo : list) {
            LfStatementExcelRow row = new LfStatementExcelRow();
            row.setTransactionTime(vo.getTransactionTime() == null ? "" : vo.getTransactionTime().format(TIME_FORMAT));
            row.setIncomeExpenseType(vo.getIncomeExpenseType());
            row.setTransactionType(vo.getTransactionType());
            row.setTransactionAmount(vo.getTransactionAmount() == null ? "" : vo.getTransactionAmount().toPlainString());
            row.setAccountBalance(vo.getAccountBalance() == null ? "" : vo.getAccountBalance().toPlainString());
            row.setBusinessDesc(vo.getBusinessDesc());
            row.setAddress(vo.getAddress());
            row.setHouseNo(vo.getHouseNo());
            row.setContractNo(vo.getContractNo());
            row.setTransactionRemark(vo.getTransactionRemark());
            row.setCounterpartyName(vo.getCounterpartyName());
            row.setBizOrderNo(vo.getBizOrderNo());
            row.setVoucherNo(vo.getVoucherNo());
            row.setPaymentNo(vo.getPaymentNo());
            rows.add(row);
        }
        ExcelBuilder.of(rows, LfStatementExcelRow.class).sheetName("理房通流水").toResponse(response);
    }

    private boolean allScope() {
        try {
            return StpUtil.hasPermission("ledger:lf:all");
        } catch (Exception e) {
            return false;
        }
    }
}
