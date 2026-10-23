package com.panjia.payroll.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.panjia.contracts.constant.BizType;
import com.panjia.contracts.port.ApprovalAction;
import com.panjia.contracts.port.ApprovalPort;
import com.panjia.contracts.port.ApprovalStartCmd;
import com.panjia.contracts.port.PeopleQueryPort;
import com.panjia.contracts.snapshot.EmployeeSnapshot;
import com.panjia.payroll.domain.SelfPurchase;
import com.panjia.payroll.mapper.SelfPurchaseMapper;
import com.panjia.payroll.service.ISelfPurchaseService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 自购单提成服务实现（warm-flow：self_purchase_approval）。
 * <p>
 * 流程：sp_start → sp_apply（提交申请，申请人）→ sp_review（总监审核）→ sp_end；
 * 总监驳回回 sp_apply。审批通过（APPROVED）后在算薪时对该经纪人在该合同号上的
 * 结佣提点额外加成（{@link #loadApprovedByEmployee}）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SelfPurchaseServiceImpl implements ISelfPurchaseService {

    /** 加成上限：+50 个百分点（防止误填） */
    private static final BigDecimal MAX_BONUS_RATE = new BigDecimal("0.50");

    private final SelfPurchaseMapper mapper;
    private final ApprovalPort approvalPort;
    private final PeopleQueryPort peopleQueryPort;

    // ==================== CRUD ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long create(SelfPurchase item, Long operatorId) {
        normalize(item);
        validate(item);
        assertNoDuplicate(item);
        item.setStatus(SelfPurchase.STATUS_DRAFT);
        item.setProcessInstanceId(null);
        item.setApplyBy(null);
        item.setApplyTime(null);
        item.setApproveBy(null);
        item.setApproveTime(null);
        item.setRejectReason(null);
        mapper.insert(item);
        log.info("[自购单提成] 登记：id={}, contractNo={}, employeeId={}, bonus={}",
            item.getId(), item.getContractNo(), item.getEmployeeId(), item.getBonusRate());
        return item.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(SelfPurchase item, Long operatorId) {
        SelfPurchase exist = requireById(item.getId());
        assertEditable(exist);
        normalize(item);
        validate(item);
        assertNoDuplicate(item);
        exist.setContractNo(item.getContractNo());
        exist.setEmployeeId(item.getEmployeeId());
        exist.setBonusRate(item.getBonusRate());
        exist.setRemark(item.getRemark());
        exist.setRejectReason(null);
        if (mapper.updateById(exist) == 0) {
            throw new ServiceException("修改失败，自购单已被他人操作，请刷新后重试");
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id, Long operatorId) {
        SelfPurchase exist = requireById(id);
        assertEditable(exist);
        mapper.deleteById(id);
        log.info("[自购单提成] 删除：id={}", id);
    }

    // ==================== 提交 / 撤销 ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void submit(Long id, Long operatorId) {
        SelfPurchase item = requireById(id);
        if (!SelfPurchase.STATUS_DRAFT.equals(item.getStatus())
            && !SelfPurchase.STATUS_REJECTED.equals(item.getStatus())) {
            throw new ServiceException("仅待提交或已驳回的自购单可提交审批");
        }
        normalize(item);
        validate(item);
        assertNoDuplicate(item);

        boolean resubmit = SelfPurchase.STATUS_REJECTED.equals(item.getStatus())
            && StringUtils.isNotBlank(item.getProcessInstanceId());
        if (resubmit) {
            // 驳回后流程停在「提交申请」节点：办理该任务重新进入总监审核
            boolean ok = approvalPort.completeAsSys(BizType.SELF_PURCHASE, id,
                ApprovalAction.PASS, "重新提交");
            if (!ok) {
                throw new ServiceException("重新提交失败，流程任务不存在，请联系管理员");
            }
        } else {
            ApprovalStartCmd cmd = buildStartCmd(item, operatorId);
            boolean ok;
            try {
                ok = approvalPort.startAndCompleteFirst(BizType.SELF_PURCHASE, id, cmd);
            } catch (Exception e) {
                log.error("[自购单提成] 流程发起异常：id={}", id, e);
                throw new ServiceException("自购单提成审批流程发起失败：{}", e.getMessage());
            }
            if (!ok) {
                throw new ServiceException("自购单提成审批流程发起失败");
            }
            Long instanceId = approvalPort.instanceId(BizType.SELF_PURCHASE, id);
            item.setProcessInstanceId(instanceId == null ? null : String.valueOf(instanceId));
        }

        item.setStatus(SelfPurchase.STATUS_SUBMITTED);
        item.setApplyBy(operatorId);
        item.setApplyTime(LocalDateTime.now());
        item.setApproveBy(null);
        item.setApproveTime(null);
        item.setRejectReason(null);
        if (mapper.updateById(item) == 0) {
            throw new ServiceException("提交失败，自购单已被他人操作，请刷新后重试");
        }
        log.info("[自购单提成] 提交审批：id={}, operatorId={}, 重提={}", id, operatorId, resubmit);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void cancel(Long id, Long operatorId) {
        SelfPurchase item = requireById(id);
        String status = item.getStatus();
        if (SelfPurchase.STATUS_SUBMITTED.equals(status)) {
            try {
                approvalPort.cancel(BizType.SELF_PURCHASE, id);
            } catch (Exception e) {
                log.error("[自购单提成] 在途流程撤销失败：id={}", id, e);
                throw new ServiceException("撤销审批流程失败：{}", e.getMessage());
            }
            item.setStatus(SelfPurchase.STATUS_DRAFT);
            item.setProcessInstanceId(null);
        } else if (SelfPurchase.STATUS_APPROVED.equals(status)) {
            // 生效中自购单作废（终态），当期未算薪批次随重算自动失去加成
            item.setStatus(SelfPurchase.STATUS_CANCELLED);
        } else {
            throw new ServiceException("仅审批中或已通过的自购单可撤销");
        }
        if (mapper.updateById(item) == 0) {
            throw new ServiceException("撤销失败，自购单已被他人操作，请刷新后重试");
        }
        log.info("[自购单提成] 撤销：id={}, 原状态={}，操作人={}", id, status, operatorId);
    }

    // ==================== 查询 ====================

    @Override
    public SelfPurchase getById(Long id) {
        return requireById(id);
    }

    @Override
    public List<SelfPurchase> list(String contractNo, Long employeeId, String status) {
        LambdaQueryWrapper<SelfPurchase> qw = new LambdaQueryWrapper<>();
        if (StringUtils.isNotBlank(contractNo)) {
            qw.like(SelfPurchase::getContractNo, contractNo.trim());
        }
        if (employeeId != null) {
            qw.eq(SelfPurchase::getEmployeeId, employeeId);
        }
        if (StringUtils.isNotBlank(status)) {
            qw.eq(SelfPurchase::getStatus, status);
        }
        qw.orderByDesc(SelfPurchase::getCreateTime);
        return mapper.selectList(qw);
    }

    @Override
    public Map<Long, Map<String, BigDecimal>> loadApprovedByEmployee(Collection<Long> employeeIds) {
        if (employeeIds == null || employeeIds.isEmpty()) {
            return Map.of();
        }
        List<SelfPurchase> items = mapper.selectList(new LambdaQueryWrapper<SelfPurchase>()
            .eq(SelfPurchase::getStatus, SelfPurchase.STATUS_APPROVED)
            .in(SelfPurchase::getEmployeeId, employeeIds));
        Map<Long, Map<String, BigDecimal>> result = new HashMap<>();
        for (SelfPurchase it : items) {
            if (it.getEmployeeId() == null || StringUtils.isBlank(it.getContractNo())) {
                continue;
            }
            BigDecimal bonus = it.getBonusRate() == null
                ? SelfPurchase.DEFAULT_BONUS_RATE : it.getBonusRate();
            result.computeIfAbsent(it.getEmployeeId(), k -> new HashMap<>())
                .merge(it.getContractNo().trim(), bonus, BigDecimal::max);
        }
        return result;
    }

    // ==================== 工作流回调 ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void handleWorkflowEvent(Long bizId, String status, String handler, String message) {
        SelfPurchase item = mapper.selectById(bizId);
        if (item == null) {
            log.warn("[自购单提成] 工作流回调单据不存在，忽略：id={}, status={}", bizId, status);
            return;
        }
        Long handlerId = parseHandlerId(handler);
        switch (status == null ? "" : status) {
            case "finish" -> {
                item.setStatus(SelfPurchase.STATUS_APPROVED);
                item.setApproveBy(handlerId);
                item.setApproveTime(LocalDateTime.now());
            }
            case "back" -> {
                item.setStatus(SelfPurchase.STATUS_REJECTED);
                item.setApproveBy(handlerId);
                item.setApproveTime(LocalDateTime.now());
                item.setRejectReason(StringUtils.isBlank(message) ? "总监驳回" : message);
            }
            case "cancel", "invalid", "termination" -> {
                item.setStatus(SelfPurchase.STATUS_DRAFT);
                item.setProcessInstanceId(null);
            }
            default -> {
                log.info("[自购单提成] 忽略流程状态：id={}, status={}", bizId, status);
                return;
            }
        }
        if (mapper.updateById(item) == 0) {
            log.warn("[自购单提成] 工作流回写失败（并发修改）：id={}, status={}", bizId, status);
        }
        log.info("[自购单提成] 工作流回写：id={}, status={}, handler={}", bizId, status, handler);
    }

    // ==================== 内部方法 ====================

    private SelfPurchase requireById(Long id) {
        SelfPurchase item = mapper.selectById(id);
        if (item == null) {
            throw new ServiceException("自购单不存在，id={}", id);
        }
        return item;
    }

    private void normalize(SelfPurchase item) {
        if (item.getContractNo() != null) {
            item.setContractNo(item.getContractNo().trim());
        }
        if (item.getBonusRate() == null) {
            item.setBonusRate(SelfPurchase.DEFAULT_BONUS_RATE);
        }
    }

    private void validate(SelfPurchase item) {
        if (StringUtils.isBlank(item.getContractNo())) {
            throw new ServiceException("请填写合同号");
        }
        if (item.getContractNo().length() > 100) {
            throw new ServiceException("合同号不能超过 100 个字符");
        }
        if (item.getEmployeeId() == null) {
            throw new ServiceException("请选择经纪人");
        }
        if (item.getBonusRate() == null || item.getBonusRate().signum() <= 0
            || item.getBonusRate().compareTo(MAX_BONUS_RATE) > 0) {
            throw new ServiceException("提点加成超出合理范围（0 ~ 0.5）");
        }
        if (item.getRemark() != null && item.getRemark().length() > 500) {
            throw new ServiceException("备注不能超过 500 字");
        }
        assertAgent(item.getEmployeeId());
    }

    /** 仅经纪人角色适用：店长/总监（按岗位或职级判定，口径同算薪引擎 resolveRole）不可登记 */
    private void assertAgent(Long employeeId) {
        EmployeeSnapshot snap = peopleQueryPort.getEmployeeSnapshot(employeeId, LocalDate.now());
        if (snap == null) {
            throw new ServiceException("经纪人不存在或已离职，请重新选择");
        }
        String position = snap.getPosition();
        if (position != null && (position.contains("店长") || position.contains("总监"))) {
            throw new ServiceException("自购单提成仅适用于经纪人角色，店长/总监不适用");
        }
        String level = snap.getLevelCode();
        if (level != null && (level.startsWith("S") || "D".equals(level))) {
            throw new ServiceException("自购单提成仅适用于经纪人角色，店长/总监不适用");
        }
    }

    /** 同一合同 + 同一经纪人仅允许一条在途/已生效记录（撤销/驳回后可重新登记） */
    private void assertNoDuplicate(SelfPurchase item) {
        Long count = mapper.selectCount(new LambdaQueryWrapper<SelfPurchase>()
            .eq(SelfPurchase::getContractNo, item.getContractNo())
            .eq(SelfPurchase::getEmployeeId, item.getEmployeeId())
            .in(SelfPurchase::getStatus, List.of(SelfPurchase.STATUS_DRAFT,
                SelfPurchase.STATUS_SUBMITTED, SelfPurchase.STATUS_APPROVED))
            .ne(item.getId() != null, SelfPurchase::getId, item.getId()));
        if (count != null && count > 0) {
            throw new ServiceException("该合同号与经纪人已存在自购单（待提交/审批中/已通过），请勿重复登记");
        }
    }

    private void assertEditable(SelfPurchase item) {
        if (!SelfPurchase.STATUS_DRAFT.equals(item.getStatus())
            && !SelfPurchase.STATUS_REJECTED.equals(item.getStatus())) {
            throw new ServiceException("仅待提交或已驳回的自购单可修改/删除");
        }
    }

    /** 发起流程命令（variables 必须可变，适配器会写入流程变量） */
    private ApprovalStartCmd buildStartCmd(SelfPurchase item, Long operatorId) {
        ApprovalStartCmd cmd = ApprovalStartCmd.of(String.valueOf(item.getId()),
            "自购单提成审批｜" + item.getContractNo());
        cmd.setHandler(String.valueOf(operatorId));
        Map<String, Object> variables = new HashMap<>(2);
        variables.put("ignore", true);
        cmd.setVariables(variables);
        return cmd;
    }

    private Long parseHandlerId(String handler) {
        if (StringUtils.isBlank(handler)) {
            return null;
        }
        try {
            return Long.valueOf(handler.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @Override
    public boolean claim(Long id) {
        return approvalPort.claim(BizType.SELF_PURCHASE, id);
    }
}
