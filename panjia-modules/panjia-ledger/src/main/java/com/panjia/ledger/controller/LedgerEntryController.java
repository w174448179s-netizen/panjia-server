package com.panjia.ledger.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.panjia.ledger.domain.LedgerEntry;
import com.panjia.ledger.dto.LedgerEntryVO;
import com.panjia.ledger.service.LedgerEntryService;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.dromara.common.satoken.utils.LoginHelper;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

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
