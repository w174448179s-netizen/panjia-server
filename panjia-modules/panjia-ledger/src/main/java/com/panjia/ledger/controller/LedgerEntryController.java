package com.panjia.ledger.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.panjia.ledger.domain.LedgerEntry;
import com.panjia.ledger.dto.LedgerEntryExcelRow;
import com.panjia.ledger.dto.LedgerEntryVO;
import com.panjia.ledger.dto.LedgerImportResultVO;
import com.panjia.ledger.service.LedgerEntryService;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.dromara.common.excel.utils.ExcelBuilder;
import org.dromara.common.satoken.utils.LoginHelper;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 部门收支录入（收入/支出两个页签共用）。
 */
@RestController
@RequestMapping("/ledger/entry")
@RequiredArgsConstructor
public class LedgerEntryController {

    private final LedgerEntryService entryService;

    @SaCheckPermission("ledger:entry:list")
    @GetMapping("/list")
    public R<List<LedgerEntryVO>> list(@RequestParam(required = false) String period,
                                       @RequestParam(required = false) String direction,
                                       @RequestParam(required = false) Long deptId,
                                       @RequestParam(required = false) Long subjectId) {
        return R.ok(entryService.list(period, direction, deptId, subjectId));
    }

    /**
     * 导入录入数据（模板列：方向 / 科目路径 / 部门 / 金额 / 生效日期 / 失效日期 / 备注）。
     * <p>整批校验通过才写入，任一行失败会返回逐行错误且不落库。</p>
     */
    @SaCheckPermission("ledger:entry:add")
    @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public R<LedgerImportResultVO> importData(@RequestPart("file") MultipartFile file) throws Exception {
        List<LedgerEntryExcelRow> rows = ExcelBuilder.read(file.getInputStream(), LedgerEntryExcelRow.class).doReadSync();
        return R.ok(entryService.importEntries(rows, LoginHelper.getUserId()));
    }

    /** 下载导入模板（仅表头） */
    @SaCheckPermission("ledger:entry:list")
    @PostMapping("/importTemplate")
    public void importTemplate(HttpServletResponse response) {
        ExcelBuilder.of(Collections.<LedgerEntryExcelRow>emptyList(), LedgerEntryExcelRow.class)
            .sheetName("部门收支录入").toResponse(response);
    }

    /** 导出当前查询条件下的录入明细 */
    @SaCheckPermission("ledger:entry:list")
    @PostMapping("/export")
    public void export(@RequestParam(required = false) String period,
                       @RequestParam(required = false) String direction,
                       @RequestParam(required = false) Long deptId,
                       @RequestParam(required = false) Long subjectId,
                       HttpServletResponse response) {
        List<LedgerEntryVO> list = entryService.list(period, direction, deptId, subjectId);
        List<LedgerEntryExcelRow> rows = new ArrayList<>(list.size());
        for (LedgerEntryVO vo : list) {
            LedgerEntryExcelRow row = new LedgerEntryExcelRow();
            row.setDirection("INCOME".equals(vo.getDirection()) ? "收入" : "支出");
            row.setSubjectPath(vo.getSubjectPath());
            row.setDeptName(vo.getDeptName());
            row.setAmount(vo.getAmount() == null ? "" : vo.getAmount().toPlainString());
            row.setStartDate(vo.getStartDate() == null ? "" : vo.getStartDate().toString());
            row.setEndDate(vo.getEndDate() == null ? "" : vo.getEndDate().toString());
            row.setRemark(vo.getRemark());
            rows.add(row);
        }
        ExcelBuilder.of(rows, LedgerEntryExcelRow.class).sheetName("部门收支录入").toResponse(response);
    }

    @SaCheckPermission("ledger:entry:add")
    @PostMapping
    public R<Long> create(@RequestBody LedgerEntry entry) {
        return R.ok(entryService.create(entry, LoginHelper.getUserId()));
    }

    @SaCheckPermission("ledger:entry:edit")
    @PutMapping
    public R<Void> update(@RequestBody LedgerEntry entry) {
        entryService.update(entry);
        return R.ok();
    }

    @SaCheckPermission("ledger:entry:remove")
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        entryService.delete(id);
        return R.ok();
    }
}
