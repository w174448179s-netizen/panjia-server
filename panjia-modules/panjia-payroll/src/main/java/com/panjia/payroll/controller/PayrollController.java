package com.panjia.payroll.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.panjia.contracts.dto.CommissionItemDTO;
import com.panjia.contracts.dto.EmployeeMainDataDTO;
import com.panjia.contracts.port.EmployeeCredentialPort;
import com.panjia.contracts.port.EmployeeMainDataQueryPort;
import com.panjia.payroll.domain.PayrollBatch;
import com.panjia.payroll.domain.PayrollDetail;
import com.panjia.payroll.domain.RuleSnapshot;
import com.panjia.payroll.domain.vo.MyPayrollDetailVo;
import com.panjia.payroll.service.PayrollBatchService;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.dromara.common.satoken.utils.LoginHelper;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.HashMap;
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
    private final EmployeeMainDataQueryPort employeeMainDataQueryPort;
    private final EmployeeCredentialPort employeeCredentialPort;

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

    /**
     * 个税回补导入：外部个税系统（扣缴端）算税后回填，覆盖个税、重算实发、重汇总批次。
     * 请求体：员工ID(字符串雪花) → 本月个税。权限沿用算薪权限（同一操作人）。
     */
    @SaCheckPermission("payroll:batch:calculate")
    @PostMapping("/{id}/tax-import")
    public R<PayrollBatch> importTax(@PathVariable Long id, @RequestBody Map<String, BigDecimal> taxByEmployeeId) {
        return R.ok(batchService.importTax(id, taxByEmployeeId, LoginHelper.getUserId()));
    }

    /** 标记发放 */
    @SaCheckPermission("payroll:batch:release")
    @PostMapping("/{id}/pay")
    public R<PayrollBatch> pay(@PathVariable Long id) {
        return R.ok(batchService.pay(id, LoginHelper.getUserId()));
    }

    /**
     * 发送工资条：算薪完成且未锁定时可发；发送后员工端「工资查询」立即可见（留痕发送人/时间）。
     * 权限沿用发放权限（同一操作环节）。
     */
    @SaCheckPermission("payroll:batch:release")
    @PostMapping("/{id}/send")
    public R<PayrollBatch> send(@PathVariable Long id) {
        return R.ok(batchService.send(id, LoginHelper.getUserId(), LoginHelper.getUsername()));
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

    /** 工资明细（一人一行聚合，前端分页展示） */
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

    /**
     * 员工核对工资：纯留痕标记（不影响工资审批流）；批次锁定后不可核对，已核对幂等返回。
     * 员工身份由后端按登录态解析。
     */
    @SaCheckPermission("payroll:my:query")
    @PostMapping("/my/detail/ack")
    public R<PayrollDetail> ackMyDetail(@RequestParam Long batchId) {
        return R.ok(batchService.ackMyDetail(LoginHelper.getUserId(), batchId));
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

    // ==================== 工资查询密码门（综合查询 → 工资查询） ====================

    /**
     * 查询当前登录员工是否已设置工资查询密码（前端决定首次进入时是否弹出设置框）。
     */
    @SaCheckPermission("payroll:my:query")
    @GetMapping("/my/password-status")
    public R<Map<String, Boolean>> passwordStatus() {
        Long userId = LoginHelper.getUserId();
        EmployeeMainDataDTO emp = employeeMainDataQueryPort.getByUserId(userId);
        Map<String, Boolean> result = new HashMap<>(2);
        boolean has = emp != null && employeeMainDataQueryPort.getSalaryQueryPasswordHash(emp.getEmployeeId()) != null;
        result.put("hasPassword", has);
        return R.ok(result);
    }

    /**
     * 验证工资查询密码。
     * <p>
     * 请求体：{ "password": "xxx" }；后端按登录态解析员工身份，不接受 employeeId 参数。
     *
     * @return 成功：R.ok()；失败：R.fail("密码错误")
     */
    @SaCheckPermission("payroll:my:query")
    @PostMapping("/my/verify-password")
    public R<Void> verifyPassword(@RequestBody Map<String, String> body) {
        String password = body.get("password");
        if (password == null || password.isBlank()) {
            return R.fail("密码不能为空");
        }
        Long userId = LoginHelper.getUserId();
        EmployeeMainDataDTO emp = employeeMainDataQueryPort.getByUserId(userId);
        if (emp == null) {
            return R.fail("员工身份不存在");
        }
        String hash = employeeMainDataQueryPort.getSalaryQueryPasswordHash(emp.getEmployeeId());
        if (hash == null) {
            return R.fail("尚未设置工资查询密码，请先设置");
        }
        boolean ok = cn.hutool.crypto.digest.BCrypt.checkpw(password, hash);
        if (!ok) {
            return R.fail("密码错误");
        }
        return R.ok();
    }

    /**
     * 设置/更新工资查询密码。
     * <p>
     * 请求体：{ "password": "xxx", "oldPassword": "xxx(修改时必填，首次设置可省略)" }
     * 后端按登录态解析员工身份，不接受 employeeId 参数。
     */
    @SaCheckPermission("payroll:my:query")
    @PutMapping("/my/password")
    public R<Void> setPassword(@RequestBody Map<String, String> body) {
        String password = body.get("password");
        if (password == null || password.length() < 4) {
            return R.fail("密码长度至少 4 位");
        }
        Long userId = LoginHelper.getUserId();
        EmployeeMainDataDTO emp = employeeMainDataQueryPort.getByUserId(userId);
        if (emp == null) {
            return R.fail("员工身份不存在");
        }
        // 如果已有密码，校验 oldPassword
        String hash = employeeMainDataQueryPort.getSalaryQueryPasswordHash(emp.getEmployeeId());
        if (hash != null) {
            String oldPassword = body.get("oldPassword");
            if (oldPassword == null || !cn.hutool.crypto.digest.BCrypt.checkpw(oldPassword, hash)) {
                return R.fail("原密码错误");
            }
        }
        employeeCredentialPort.setSalaryQueryPassword(emp.getEmployeeId(), password);
        return R.ok();
    }
}
