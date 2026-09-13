package com.panjia.payroll.controller;

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
 */
@RestController
@RequestMapping("/payroll/rule")
@RequiredArgsConstructor
public class RuleController {

    private final RuleService ruleService;

    @GetMapping("/rank")
    public R<List<RankRule>> rankList() {
        return R.ok(ruleService.listRankRules());
    }

    @PostMapping("/rank")
    public R<Void> saveRank(@RequestBody RankRule rule) {
        ruleService.saveRankRule(rule);
        return R.ok();
    }

    @GetMapping("/policy")
    public R<List<PolicyRule>> policyList() {
        return R.ok(ruleService.listPolicyRules());
    }

    @PostMapping("/policy")
    public R<Void> savePolicy(@RequestBody PolicyRule rule) {
        ruleService.savePolicyRule(rule);
        return R.ok();
    }

    @GetMapping("/conversion")
    public R<List<ConversionRule>> conversionList() {
        return R.ok(ruleService.listConversionRules());
    }

    @PostMapping("/conversion")
    public R<Void> saveConversion(@RequestBody ConversionRule rule) {
        ruleService.saveConversionRule(rule);
        return R.ok();
    }

    @GetMapping("/snapshot/{batchId}")
    public R<RuleSnapshot> snapshot(@PathVariable Long batchId) {
        return R.ok(ruleService.getSnapshot(batchId));
    }
}
