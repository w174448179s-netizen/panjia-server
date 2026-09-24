package com.panjia.payroll.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.panjia.contracts.dto.CommissionItemDTO;
import com.panjia.payroll.domain.PayrollBatch;
import com.panjia.payroll.domain.PayrollDetail;
import com.panjia.payroll.domain.RuleSnapshot;
import com.panjia.payroll.domain.vo.MyPayrollDetailVo;
import com.panjia.payroll.service.PayrollBatchService;
import com.panjia.payroll.tools.HistoryPayrollImporter;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.dromara.common.log.annotation.Log;
import org.dromara.common.log.enums.BusinessType;
import org.dromara.common.satoken.utils.LoginHelper;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.util.List;
import java.util.Map;

/**
 * 工资批次管理。
 * <p>
 * 审批动作（审核通过 / 驳回 / 锁定）已全部收敛到 warm-flow 的 payroll_batch 流程，
 * 由「我的待办」按 flow_user 名单判权办理；本控制器只保留
 * 算薪（calculate）/ 提交（submit）/ 标记发放（pay）与查询端点。
 */
@RestController
@RequestMapping("/payroll/batch")
@RequiredArgsConstructor
public class PayrollController {

    private final PayrollBatchService batchService;
    private final HistoryPayrollImporter historyImporter;

    /** 创建批次 */
    @SaCheckPermission("payroll:batch:add")
    @PostMapping
    public R<PayrollBatch> create(@RequestBody Map<String, String> body) {
        String period = body.get("period");
        String deptScope = body.getOrDefault("deptScope", "ALL");
        return R.ok(batchService.createBatch(period, deptScope, LoginHelper.getUserId()));
    }

    /** 算薪 */
    @SaCheckPermission("payroll:batch:calculate")
    @PostMapping("/{id}/calculate")
    public R<PayrollBatch> calculate(@PathVariable Long id) {
        return R.ok(batchService.calculate(id, LoginHelper.getUserId()));
    }

    /** 提交审核（发起 payroll_batch 流程；审批与锁定由工作流节点办理） */
    @SaCheckPermission("payroll:batch:submit")
    @PostMapping("/{id}/submit")
    public R<PayrollBatch> submit(@PathVariable Long id) {
        return R.ok(batchService.submit(id, LoginHelper.getUserId()));
    }

    /** 标记发放 */
    @SaCheckPermission("payroll:batch:release")
    @PostMapping("/{id}/pay")
    public R<PayrollBatch> pay(@PathVariable Long id) {
        return R.ok(batchService.pay(id, LoginHelper.getUserId()));
    }

    /** 批次列表 */
    @SaCheckPermission("payroll:batch:list")
    @GetMapping
    public R<List<PayrollBatch>> list(@RequestParam(required = false) String period) {
        return R.ok(batchService.list(period));
    }

    /** 批次详情 */
    @SaCheckPermission("payroll:batch:list")
    @GetMapping("/{id}")
    public R<PayrollBatch> detail(@PathVariable Long id) {
        return R.ok(batchService.get(id));
    }

    /** 工资明细 */
    @SaCheckPermission("payroll:detail:list")
    @GetMapping("/{id}/details")
    public R<List<PayrollDetail>> details(@PathVariable Long id) {
        return R.ok(batchService.listDetails(id));
    }

    /** 规则快照 */
    @SaCheckPermission("payroll:batch:list")
    @GetMapping("/{id}/snapshot")
    public R<RuleSnapshot> snapshot(@PathVariable Long id) {
        return R.ok(batchService.getRuleSnapshot(id));
    }

    // ==================== 本人工资查询（综合查询 → 工资查询） ====================

    /** 我的工资批次列表（仅含本人有明细的批次，期间倒序） */
    @SaCheckPermission("payroll:my:query")
    @GetMapping("/my/batches")
    public R<List<PayrollBatch>> myBatches() {
        return R.ok(batchService.listMyBatches(LoginHelper.getUserId()));
    }

    /** 我的工资明细（员工身份由后端按登录态解析，不接受员工参数） */
    @SaCheckPermission("payroll:my:query")
    @GetMapping("/my/detail")
    public R<MyPayrollDetailVo> myDetail(@RequestParam Long batchId) {
        return R.ok(batchService.getMyDetail(LoginHelper.getUserId(), batchId));
    }

    /** 我的结佣追溯（本人工资构成中每笔结佣明细，员工身份后端解析） */
    @SaCheckPermission("payroll:my:query")
    @GetMapping("/my/commission-trace")
    public R<List<CommissionItemDTO>> myCommissionTrace(@RequestParam String period) {
        return R.ok(batchService.listMyCommissionTrace(LoginHelper.getUserId(), period));
    }

    /** 我的门店新签明细（店长查看所在门店团队成员的新签业绩） */
    @SaCheckPermission("payroll:my:query")
    @GetMapping("/my/team-newsign")
    public R<List<CommissionItemDTO>> myTeamNewSign(@RequestParam String period) {
        return R.ok(batchService.listMyTeamNewSign(LoginHelper.getUserId(), period));
    }

    /** 组织视角结佣追溯（总监/财务查指定员工的结佣明细） */
    @SaCheckPermission("payroll:detail:list")
    @GetMapping("/commission-trace")
    public R<List<CommissionItemDTO>> commissionTrace(@RequestParam String period, @RequestParam Long employeeId) {
        return R.ok(batchService.listCommissionTrace(period, employeeId));
    }

    /** 组织视角门店新签明细（总监/财务查指定门店团队成员的新签业绩） */
    @SaCheckPermission("payroll:detail:list")
    @GetMapping("/team-newsign")
    public R<List<CommissionItemDTO>> teamNewSign(@RequestParam String period, @RequestParam Long deptId) {
        return R.ok(batchService.listTeamNewSign(period, deptId));
    }

    /** 导出用：指定期间全部结佣明细（不限门店/员工） */
    @SaCheckPermission("payroll:detail:list")
    @GetMapping("/all-commission")
    public R<List<CommissionItemDTO>> allCommission(@RequestParam String period) {
        return R.ok(batchService.listAllCommissionForExport(period));
    }

    /** 导出用：指定期间全部新签明细（不限门店/员工） */
    @SaCheckPermission("payroll:batch:add")
    @GetMapping("/all-newsign")
    public R<List<CommissionItemDTO>> allNewSign(@RequestParam String period) {
        return R.ok(batchService.listAllNewSignForExport(period));
    }

    /**
     * 历史工资 Excel 导入（仅超级管理员）：上传 xlsx 文件，回写工资批次+明细+业绩事实。
     * @param period 工资归属月（如 2026-07）
     * @param file xlsx 文件（7 个 sheet：工资表/店长/总监/新签业绩/结佣业绩/人事数据/绩效和扣款）
     */
    @SaCheckPermission("payroll:history:import")
    @Log(title = "历史工资导入", businessType = BusinessType.IMPORT)
    @PostMapping("/history-import")
    public R<String> historyImport(@RequestParam String period, @RequestParam("file") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return R.fail("文件不能为空");
        }
        try (InputStream is = file.getInputStream()) {
            String result = historyImporter.importFromStream(period, is);
            return R.ok(result);
        } catch (Exception e) {
            return R.fail("导入失败：" + e.getMessage());
        }
    }
}
