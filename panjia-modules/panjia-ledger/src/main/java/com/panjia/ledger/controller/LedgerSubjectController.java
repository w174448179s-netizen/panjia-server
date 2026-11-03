package com.panjia.ledger.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.panjia.ledger.domain.LedgerSubject;
import com.panjia.ledger.service.LedgerSubjectService;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
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
 * 收支科目配置。
 */
@RestController
@RequestMapping("/ledger/subject")
@RequiredArgsConstructor
public class LedgerSubjectController {

    private final LedgerSubjectService subjectService;

    @SaCheckPermission("ledger:subject:list")
    @GetMapping("/list")
    public R<List<LedgerSubject>> list(@RequestParam(required = false) String direction) {
        return R.ok(subjectService.list(direction));
    }

    @SaCheckPermission("ledger:subject:add")
    @PostMapping
    public R<Long> create(@RequestBody LedgerSubject subject) {
        return R.ok(subjectService.create(subject));
    }

    @SaCheckPermission("ledger:subject:edit")
    @PutMapping
    public R<Void> update(@RequestBody LedgerSubject subject) {
        subjectService.update(subject);
        return R.ok();
    }

    @SaCheckPermission("ledger:subject:remove")
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        subjectService.delete(id);
        return R.ok();
    }
}
