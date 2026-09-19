package com.panjia.payroll.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.panjia.contracts.constant.BizType;
import com.panjia.contracts.port.ApprovalAction;
import com.panjia.contracts.port.ApprovalPort;
import com.panjia.contracts.port.ApprovalStartCmd;
import com.panjia.payroll.domain.RateAdjust;
import com.panjia.payroll.dto.RateAdjustItem;
import com.panjia.payroll.mapper.RateAdjustMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 提成点调整服务实现（warm-flow：rate_adjust_approval）。
 * <p>
 * 流程：ra_start → ra_apply（提交申请，申请人）→ ra_review（总监审核）→ ra_end；
 * 总监驳回回 ra_apply。审批通过（APPROVED）后按 start_month ~ end_month
 * 区间在算薪时自动叠加到提成比例（{@link #loadEffectiveForPeriod}）。
 * 未买社保扣点不经本服务：由档案参保事实自动判断（引擎 policy.noSocialDeduct）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RateAdjustServiceImpl implements RateAdjustService {

    /** 人工可登记的调整类型（NO_SOCIAL 为档案自动判断，禁止手工登记） */
    private static final Set<String> MANUAL_TYPES = Set.of("PHONE_CHECK", "PERSONAL");

    private final RateAdjustMapper mapper;
    private final ApprovalPort approvalPort;

    // ==================== CRUD ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long create(RateAdjust item, Long operatorId) {
        validate(item);
        item.setStatus(RateAdjust.STATUS_DRAFT);
        item.setProcessInstanceId(null);
        item.setApplyBy(null);
        item.setApplyTime(null);
        item.setApproveBy(null);
        item.setApproveTime(null);
        item.setRejectReason(null);
        mapper.insert(item);
        log.info("[提成点调整] 登记：id={}, employeeId={}, type={}, rate={}, 区间={}~{}",
            item.getId(), item.getEmployeeId(), item.getAdjustType(),
            item.getAdjustRate(), item.getStartMonth(), item.getEndMonth());
        return item.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(RateAdjust item, Long operatorId) {
        RateAdjust exist = requireById(item.getId());
        assertEditable(exist);
        validate(item);
        exist.setEmployeeId(item.getEmployeeId());
        exist.setAdjustType(item.getAdjustType());
        exist.setAdjustRate(item.getAdjustRate());
        exist.setStartMonth(item.getStartMonth());
        exist.setEndMonth(item.getEndMonth());
        exist.setReason(item.getReason());
        exist.setRejectReason(null);
        if (mapper.updateById(exist) == 0) {
            throw new ServiceException("修改失败，调整单已被他人操作，请刷新后重试");
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id, Long operatorId) {
        RateAdjust exist = requireById(id);
        assertEditable(exist);
        mapper.deleteById(id);
        log.info("[提成点调整] 删除：id={}", id);
    }

    // ==================== 提交 / 撤销 ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void submit(Long id, Long operatorId) {
        RateAdjust item = requireById(id);
        if (!RateAdjust.STATUS_DRAFT.equals(item.getStatus())
            && !RateAdjust.STATUS_REJECTED.equals(item.getStatus())) {
            throw new ServiceException("仅待提交或已驳回的调整单可提交审批");
        }
        validate(item);

        boolean resubmit = RateAdjust.STATUS_REJECTED.equals(item.getStatus())
            && StringUtils.isNotBlank(item.getProcessInstanceId());
        if (resubmit) {
            // 驳回后流程停在「提交申请」节点：办理该任务重新进入总监审核
            boolean ok = approvalPort.completeAsSys(BizType.RATE_ADJUST, id,
                ApprovalAction.PASS, "重新提交");
            if (!ok) {
                throw new ServiceException("重新提交失败，流程任务不存在，请联系管理员");
            }
        } else {
            ApprovalStartCmd cmd = buildStartCmd(item, operatorId);
            boolean ok;
            try {
                ok = approvalPort.startAndCompleteFirst(BizType.RATE_ADJUST, id, cmd);
            } catch (Exception e) {
                log.error("[提成点调整] 流程发起异常：id={}", id, e);
                throw new ServiceException("提成点调整审批流程发起失败：{}", e.getMessage());
            }
            if (!ok) {
                throw new ServiceException("提成点调整审批流程发起失败");
            }
            Long instanceId = approvalPort.instanceId(BizType.RATE_ADJUST, id);
            item.setProcessInstanceId(instanceId == null ? null : String.valueOf(instanceId));
        }

        item.setStatus(RateAdjust.STATUS_SUBMITTED);
        item.setApplyBy(operatorId);
        item.setApplyTime(java.time.LocalDateTime.now());
        item.setApproveBy(null);
        item.setApproveTime(null);
        item.setRejectReason(null);
        if (mapper.updateById(item) == 0) {
            throw new ServiceException("提交失败，调整单已被他人操作，请刷新后重试");
        }
        log.info("[提成点调整] 提交审批：id={}, operatorId={}, 重提={}", id, operatorId, resubmit);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void cancel(Long id, Long operatorId) {
        RateAdjust item = requireById(id);
        String status = item.getStatus();
        if (RateAdjust.STATUS_SUBMITTED.equals(status)) {
            // 撤回在途流程，回待提交可修改
            try {
                approvalPort.cancel(BizType.RATE_ADJUST, id);
            } catch (Exception e) {
                log.error("[提成点调整] 在途流程撤销失败：id={}", id, e);
                throw new ServiceException("撤销审批流程失败：{}", e.getMessage());
            }
            item.setStatus(RateAdjust.STATUS_DRAFT);
            item.setProcessInstanceId(null);
        } else if (RateAdjust.STATUS_APPROVED.equals(status)) {
            // 生效中调整作废（终态），当期未算薪批次随重算自动失去该调整
            item.setStatus(RateAdjust.STATUS_CANCELLED);
        } else {
            throw new ServiceException("仅审批中或已通过的调整单可撤销");
        }
        if (mapper.updateById(item) == 0) {
            throw new ServiceException("撤销失败，调整单已被他人操作，请刷新后重试");
        }
        log.info("[提成点调整] 撤销：id={}, 原状态={}，操作人={}", id, status, operatorId);
    }

    // ==================== 查询 ====================

    @Override
    public RateAdjust getById(Long id) {
        return requireById(id);
    }

    @Override
    public List<RateAdjust> list(Long employeeId, String adjustType, String status, String period) {
        LambdaQueryWrapper<RateAdjust> qw = new LambdaQueryWrapper<>();
        if (employeeId != null) {
            qw.eq(RateAdjust::getEmployeeId, employeeId);
        }
        if (StringUtils.isNotBlank(adjustType)) {
            qw.eq(RateAdjust::getAdjustType, adjustType);
        }
        if (StringUtils.isNotBlank(status)) {
            qw.eq(RateAdjust::getStatus, status);
        }
        if (StringUtils.isNotBlank(period)) {
            // 命中生效区间：start_month ≤ period ≤ end_month（end 为 null 视为长期有效）
            qw.eq(RateAdjust::getStartMonth, period.trim()).or(w2 -> {
                w2.lt(RateAdjust::getStartMonth, period.trim());
                w2.and(w3 -> w3.isNull(RateAdjust::getEndMonth)
                    .or().ge(RateAdjust::getEndMonth, period.trim()));
            });
            qw.eq(RateAdjust::getStatus, RateAdjust.STATUS_APPROVED);
        }
        qw.orderByDesc(RateAdjust::getCreateTime);
        return mapper.selectList(qw);
    }

    @Override
    public Map<Long, List<RateAdjustItem>> loadEffectiveForPeriod(String period) {
        List<RateAdjust> items = mapper.selectList(new LambdaQueryWrapper<RateAdjust>()
            .eq(RateAdjust::getStatus, RateAdjust.STATUS_APPROVED)
            .le(RateAdjust::getStartMonth, period)
            .and(w -> w.isNull(RateAdjust::getEndMonth).or().ge(RateAdjust::getEndMonth, period)));
        Map<Long, List<RateAdjustItem>> result = new HashMap<>();
        for (RateAdjust it : items) {
            if (it.getEmployeeId() == null) {
                continue;
            }
            RateAdjustItem dto = new RateAdjustItem();
            dto.setType(it.getAdjustType());
            dto.setRate(it.getAdjustRate());
            dto.setReason(it.getReason());
            dto.setSource("APPROVAL");
            dto.setAdjustId(it.getId());
            result.computeIfAbsent(it.getEmployeeId(), k -> new ArrayList<>()).add(dto);
        }
        return result;
    }

    // ==================== 工作流回调 ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void handleWorkflowEvent(Long bizId, String status, String handler, String message) {
        RateAdjust item = mapper.selectById(bizId);
        if (item == null) {
            log.warn("[提成点调整] 工作流回调调整单不存在，忽略：id={}, status={}", bizId, status);
            return;
        }
        Long handlerId = parseHandlerId(handler);
        switch (status == null ? "" : status) {
            case "finish" -> {
                item.setStatus(RateAdjust.STATUS_APPROVED);
                item.setApproveBy(handlerId);
                item.setApproveTime(java.time.LocalDateTime.now());
            }
            case "back" -> {
                item.setStatus(RateAdjust.STATUS_REJECTED);
                item.setApproveBy(handlerId);
                item.setApproveTime(java.time.LocalDateTime.now());
                item.setRejectReason(StringUtils.isBlank(message) ? "总监驳回" : message);
            }
            case "cancel", "invalid", "termination" -> {
                item.setStatus(RateAdjust.STATUS_DRAFT);
                item.setProcessInstanceId(null);
            }
            default -> {
                log.info("[提成点调整] 忽略流程状态：id={}, status={}", bizId, status);
                return;
            }
        }
        if (mapper.updateById(item) == 0) {
            log.warn("[提成点调整] 工作流回写失败（并发修改）：id={}, status={}", bizId, status);
        }
        log.info("[提成点调整] 工作流回写：id={}, status={}, handler={}", bizId, status, handler);
    }

    // ==================== 内部方法 ====================

    private RateAdjust requireById(Long id) {
        RateAdjust item = mapper.selectById(id);
        if (item == null) {
            throw new ServiceException("调整单不存在，id={}", id);
        }
        return item;
    }

    private void validate(RateAdjust item) {
        if (item.getEmployeeId() == null) {
            throw new ServiceException("请选择员工");
        }
        if (item.getAdjustType() == null || !MANUAL_TYPES.contains(item.getAdjustType())) {
            throw new ServiceException("调整类型不正确（未买社保扣点由档案参保状态自动判断，无需登记）");
        }
        if (item.getAdjustRate() == null || item.getAdjustRate().signum() == 0) {
            throw new ServiceException("请填写调整点数（扣点为负值，如 -0.02）");
        }
        if (item.getAdjustRate().compareTo(new java.math.BigDecimal("-0.5")) < 0
            || item.getAdjustRate().compareTo(java.math.BigDecimal.ZERO) > 0) {
            throw new ServiceException("调整点数超出合理范围（0 ~ -0.5）");
        }
        String start = requireMonth(item.getStartMonth(), "生效起始月");
        String end = item.getEndMonth();
        if (StringUtils.isNotBlank(end)) {
            end = requireMonth(end, "生效结束月");
            if (YearMonth.parse(end).isBefore(YearMonth.parse(start))) {
                throw new ServiceException("生效结束月不能早于起始月");
            }
        } else {
            item.setEndMonth(null);
        }
        if (StringUtils.isBlank(item.getReason())) {
            throw new ServiceException("请填写调整原因");
        }
        if (item.getReason().length() > 500) {
            throw new ServiceException("调整原因不能超过 500 字");
        }
    }

    private String requireMonth(String month, String label) {
        if (StringUtils.isBlank(month)) {
            throw new ServiceException(label + "不能为空");
        }
        try {
            return YearMonth.parse(month.trim()).toString();
        } catch (DateTimeParseException e) {
            throw new ServiceException(label + "格式不正确，应为 YYYY-MM：{}", month);
        }
    }

    private void assertEditable(RateAdjust item) {
        if (!RateAdjust.STATUS_DRAFT.equals(item.getStatus())
            && !RateAdjust.STATUS_REJECTED.equals(item.getStatus())) {
            throw new ServiceException("仅待提交或已驳回的调整单可修改/删除");
        }
    }

    /** 发起流程命令（variables 必须可变，适配器会写入流程变量） */
    private ApprovalStartCmd buildStartCmd(RateAdjust item, Long operatorId) {
        ApprovalStartCmd cmd = ApprovalStartCmd.of(String.valueOf(item.getId()),
            "提成点调整审批｜" + item.getAdjustType() + "｜" + item.getAdjustRate());
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
}
