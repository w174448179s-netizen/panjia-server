package com.panjia.payroll.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.annotation.SaMode;
import com.panjia.payroll.domain.ConversionRule;
import com.panjia.payroll.domain.PolicyRule;
import com.panjia.payroll.domain.RankRule;
import com.panjia.payroll.domain.RuleSnapshot;
import com.panjia.payroll.service.RuleService;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 薪酬规则配置（职级/政策/折算，只影响新算月份，不改历史）。
 * <p>配置类接口仅对持有「薪酬规则配置」权限的角色开放（当前仅超管），
 * 避免任意登录用户改写算薪口径。</p>
 */
@RestController
@RequestMapping("/payroll/rule")
@RequiredArgsConstructor
public class RuleController {

    private final RuleService ruleService;

    @SaCheckPermission("payroll:rule:list")
    @GetMapping("/rank")
    public R<List<RankRule>> rankList() {
        return R.ok(ruleService.listRankRules());
    }

    @SaCheckPermission("payroll:rule:list")
    @PostMapping("/rank")
    public R<Void> saveRank(@RequestBody RankRule rule) {
        ruleService.saveRankRule(rule);
        return R.ok();
    }

    @SaCheckPermission("payroll:rule:list")
    @GetMapping("/policy")
    public R<List<PolicyRule>> policyList() {
        return R.ok(ruleService.listPolicyRules());
    }

    @SaCheckPermission("payroll:rule:list")
    @PostMapping("/policy")
    public R<Void> savePolicy(@RequestBody PolicyRule rule) {
        ruleService.savePolicyRule(rule);
        return R.ok();
    }

    @SaCheckPermission("payroll:rule:list")
    @GetMapping("/conversion")
    public R<List<ConversionRule>> conversionList() {
        return R.ok(ruleService.listConversionRules());
    }

    @SaCheckPermission("payroll:rule:list")
    @PostMapping("/conversion")
    public R<Void> saveConversion(@RequestBody ConversionRule rule) {
        ruleService.saveConversionRule(rule);
        return R.ok();
    }

    /** 批次规则快照：算薪批次页与规则页都可能读取，故取二者权限之一的 OR 语义。 */
    @SaCheckPermission(value = {"payroll:rule:list", "payroll:batch:list"}, mode = SaMode.OR)
    @GetMapping("/snapshot/{batchId}")
    public R<RuleSnapshot> snapshot(@PathVariable Long batchId) {
        return R.ok(ruleService.getSnapshot(batchId));
    }
}
