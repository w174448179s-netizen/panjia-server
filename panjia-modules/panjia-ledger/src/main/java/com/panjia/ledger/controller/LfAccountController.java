package com.panjia.ledger.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.panjia.ledger.domain.LfAccount;
import com.panjia.ledger.service.LfAccountService;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.PageResult;
import org.dromara.common.core.domain.R;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.satoken.utils.LoginHelper;
import org.springframework.web.bind.annotation.*;

/**
 * 部门收支 · 理房通账户配置。
 */
@RestController
@RequestMapping("/ledger/lfAccount")
@RequiredArgsConstructor
public class LfAccountController {

    private final LfAccountService accountService;

    @SaCheckPermission("ledger:lf:account:list")
    @GetMapping("/list")
    public R<PageResult<LfAccount>> list(@RequestParam(required = false) String keyword,
                                         @RequestParam(required = false) String status,
                                         @ModelAttribute PageQuery pageQuery) {
        return R.ok(accountService.list(keyword, status, pageQuery));
    }

    @SaCheckPermission("ledger:lf:account:add")
    @PostMapping
    public R<Long> create(@RequestBody LfAccount account) {
        return R.ok(accountService.create(account, LoginHelper.getUserId()));
    }

    @SaCheckPermission("ledger:lf:account:edit")
    @PutMapping
    public R<Void> update(@RequestBody LfAccount account) {
        accountService.update(account, LoginHelper.getUserId());
        return R.ok();
    }

    @SaCheckPermission("ledger:lf:account:remove")
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        accountService.delete(id);
        return R.ok();
    }
}
