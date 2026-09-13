package com.panjia.payroll.controller;

import com.panjia.payroll.domain.ManualItem;
import com.panjia.payroll.service.ManualItemService;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.dromara.common.satoken.utils.LoginHelper;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 手工录入项（奖金/其他收入/其他支出）。
 */
@RestController
@RequestMapping("/payroll/manual-item")
@RequiredArgsConstructor
public class ManualItemController {

    private final ManualItemService manualItemService;

    @PostMapping
    public R<Long> create(@RequestBody ManualItem item) {
        return R.ok(manualItemService.create(item, LoginHelper.getUserId()));
    }

    @GetMapping
    public R<List<ManualItem>> list(@RequestParam String period) {
        return R.ok(manualItemService.listByPeriod(period));
    }

    @GetMapping("/{id}")
    public R<ManualItem> get(@PathVariable Long id) {
        return R.ok(manualItemService.getById(id));
    }

    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        manualItemService.delete(id, LoginHelper.getUserId());
        return R.ok();
    }
}
