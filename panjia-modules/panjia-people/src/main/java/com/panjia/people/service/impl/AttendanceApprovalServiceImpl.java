package com.panjia.people.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.panjia.contracts.constant.BizType;
import com.panjia.contracts.dto.AttendanceApprovalStatusDTO;
import com.panjia.contracts.port.ApprovalAction;
import com.panjia.contracts.port.ApprovalPort;
import com.panjia.contracts.port.ApprovalStartCmd;
import com.panjia.people.domain.AttendanceApproval;
import com.panjia.people.domain.AttendanceRecord;
import com.panjia.people.domain.Employee;
import com.panjia.people.dto.AttendanceApprovalVO;
import com.panjia.people.mapper.AttendanceApprovalMapper;
import com.panjia.people.mapper.AttendanceRecordMapper;
import com.panjia.people.mapper.EmployeeMapper;
import com.panjia.people.service.AttendanceApprovalService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 考勤审批服务实现（warm-flow：attendance_approval 考勤月度审批）。
 * <p>
 * 流程：att_start → att_submit（提交考勤，人事）→ att_review（总监审核，
 * 节点 ext AutoApproval 24h 超时自动通过）→ att_end；总监驳回回 att_submit。
 * <p>
 * 提交时按"异常考勤"聚合快照（迟到次数/迟到分/缺卡/旷工/请假 任一 >0 的行），
 * 只有异常行需要总监审阅，正常行免审；快照定格在审批单上，办理弹窗展示。
 * 状态回写唯一入口是工作流事件（handleWorkflowEvent），无业务直批路径。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AttendanceApprovalServiceImpl implements AttendanceApprovalService {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final AttendanceApprovalMapper approvalMapper;
    private final AttendanceRecordMapper attendanceMapper;
    private final EmployeeMapper employeeMapper;
    private final ApprovalPort approvalPort;

    @Override
    public AttendanceApprovalVO getByPeriod(String period) {
        AttendanceApprovalVO vo = toVo(selectByPeriod(period), period);
        // 该月是否有考勤数据（算薪页创建批次前用于「无考勤确认」提示）
        vo.setDataExists(countByPeriod(period) > 0);
        return vo;
    }

    @Override
    public AttendanceApprovalVO getByBizId(Long bizId) {
        AttendanceApproval entity = requireById(bizId);
        return toVo(entity, entity.getPeriod());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void submit(String period, Long operatorId) {
        validatePeriod(period);
        AttendanceApproval entity = selectByPeriod(period);
        if (entity != null && AttendanceApproval.STATUS_SUBMITTED.equals(entity.getStatus())) {
            throw new ServiceException("该期间考勤审批正在流程中，请等待总监审核或超时自动通过");
        }
        if (entity == null) {
            entity = new AttendanceApproval();
            entity.setPeriod(period);
            entity.setStatus(AttendanceApproval.STATUS_DRAFT);
            approvalMapper.insert(entity);
        }

        // 异常考勤快照（含"该期间有数据"校验）；驳回后重提也重新定格最新数据
        String snapshotJson = buildSnapshotJson(YearMonth.parse(period.trim()), entity.getId());

        boolean resubmit = AttendanceApproval.STATUS_REJECTED.equals(entity.getStatus())
            && StringUtils.isNotBlank(entity.getProcessInstanceId());
        if (resubmit) {
            // 驳回后流程停在「提交考勤」节点：办理该任务重新进入总监审核
            boolean ok = approvalPort.completeAsSys(BizType.ATTENDANCE_APPROVAL, entity.getId(),
                ApprovalAction.PASS, "重新提交");
            if (!ok) {
                throw new ServiceException("重新提交失败，流程任务不存在，请联系管理员");
            }
        } else {
            // 同一单据已有历史流程实例（已通过/已驳回且回调未回写实例ID）：
            // 先撤销旧实例再重新发起，否则引擎按 businessId 校验报「该单据已完成申请」
            if (approvalPort.instanceId(BizType.ATTENDANCE_APPROVAL, entity.getId()) != null) {
                approvalPort.cancel(BizType.ATTENDANCE_APPROVAL, entity.getId());
            }
            // 首次提交：发起流程并自动办理「提交考勤」首节点
            ApprovalStartCmd cmd = buildStartCmd(entity, operatorId);
            boolean ok;
            try {
                ok = approvalPort.startAndCompleteFirst(BizType.ATTENDANCE_APPROVAL, entity.getId(), cmd);
            } catch (Exception e) {
                log.error("[考勤审批] 流程发起异常：approvalId={}", entity.getId(), e);
                throw new ServiceException("考勤审批流程发起失败：{}", e.getMessage());
            }
            if (!ok) {
                throw new ServiceException("考勤审批流程发起失败");
            }
            Long instanceId = approvalPort.instanceId(BizType.ATTENDANCE_APPROVAL, entity.getId());
            entity.setProcessInstanceId(instanceId == null ? null : String.valueOf(instanceId));
        }

        entity.setSnapshot(snapshotJson);
        entity.setStatus(AttendanceApproval.STATUS_SUBMITTED);
        entity.setSubmitBy(operatorId);
        entity.setSubmitTime(LocalDateTime.now());
        entity.setApproveBy(null);
        entity.setApproveTime(null);
        entity.setRejectReason(null);
        if (approvalMapper.updateById(entity) == 0) {
            throw new ServiceException("提交失败，审批单已被他人操作，请刷新后重试");
        }
        log.info("[考勤审批] 提交审批：period={}, approvalId={}, operatorId={}, 重提={}",
            period, entity.getId(), operatorId, resubmit);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void handleWorkflowEvent(Long bizId, String status, String handler, String message) {
        AttendanceApproval entity = approvalMapper.selectById(bizId);
        if (entity == null) {
            log.warn("[考勤审批] 工作流回调审批单不存在，忽略：id={}, status={}", bizId, status);
            return;
        }
        Long handlerId = parseHandlerId(handler);
        switch (status == null ? "" : status) {
            case "finish" -> {
                entity.setStatus(AttendanceApproval.STATUS_APPROVED);
                entity.setApproveBy(handlerId);
                entity.setApproveTime(LocalDateTime.now());
            }
            case "back" -> {
                entity.setStatus(AttendanceApproval.STATUS_REJECTED);
                entity.setApproveBy(handlerId);
                entity.setApproveTime(LocalDateTime.now());
                entity.setRejectReason(StringUtils.isBlank(message) ? "总监驳回" : message);
            }
            case "cancel", "invalid", "termination" -> entity.setStatus(AttendanceApproval.STATUS_DRAFT);
            default -> {
                log.info("[考勤审批] 忽略流程状态：id={}, status={}", bizId, status);
                return;
            }
        }
        if (approvalMapper.updateById(entity) == 0) {
            log.warn("[考勤审批] 工作流回写失败（并发修改）：id={}, status={}", bizId, status);
        }
        log.info("[考勤审批] 工作流回写：period={}, status={}, handler={}", entity.getPeriod(), status, handler);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void invalidateOnDataChange(String period) {
        AttendanceApproval entity = selectByPeriod(period);
        if (entity == null) {
            return;
        }
        String status = entity.getStatus();
        if (AttendanceApproval.STATUS_SUBMITTED.equals(status)
            || AttendanceApproval.STATUS_APPROVED.equals(status)) {
            // 在途/已完成流程均需撤销（数据已变，旧流程结果不再有效）：
            // 只回退审批单不撤实例，会让"我的已办/详情"残留旧流程状态，与实际不符
            approvalPort.cancel(BizType.ATTENDANCE_APPROVAL, entity.getId());
            // 校验实例确已清理，防止撤销失败产生僵尸实例
            if (approvalPort.instanceId(BizType.ATTENDANCE_APPROVAL, entity.getId()) != null) {
                throw new ServiceException("考勤审批流程撤销失败，请稍后重试");
            }
            entity.setProcessInstanceId(null);
            entity.setStatus(AttendanceApproval.STATUS_DRAFT);
            if (approvalMapper.updateById(entity) == 0) {
                log.warn("[考勤审批] 失效审批单失败（并发修改）：period={}", period);
                return;
            }
            log.info("[考勤审批] 考勤数据变更，审批单失效回待提交：period={}，原状态={}", period, status);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void approveForHistory(String period) {
        if (StringUtils.isBlank(period)) {
            return;
        }
        String p = period.trim();
        AttendanceApproval entity = selectByPeriod(p);
        if (entity == null) {
            entity = new AttendanceApproval();
            entity.setPeriod(p);
            entity.setStatus(AttendanceApproval.STATUS_APPROVED);
            entity.setSubmitTime(LocalDateTime.now());
            entity.setApproveTime(LocalDateTime.now());
            approvalMapper.insert(entity);
            // 快照在拿到单据 ID 后补写（历史补录无流程实例/操作人）
            entity.setSnapshot(buildSnapshotJson(YearMonth.parse(p), entity.getId()));
            if (approvalMapper.updateById(entity) == 0) {
                log.warn("[考勤审批] 历史审批单快照补写失败（并发修改）：period={}", p);
            }
            log.info("[考勤审批] 历史导入新增 APPROVED 审批单：period={}", p);
            return;
        }
        if (AttendanceApproval.STATUS_APPROVED.equals(entity.getStatus())) {
            log.info("[考勤审批] 历史导入审批单已为 APPROVED，跳过：period={}", p);
            return;
        }
        entity.setStatus(AttendanceApproval.STATUS_APPROVED);
        entity.setApproveTime(LocalDateTime.now());
        entity.setSnapshot(buildSnapshotJson(YearMonth.parse(p), entity.getId()));
        if (approvalMapper.updateById(entity) == 0) {
            log.warn("[考勤审批] 历史审批单收敛失败（并发修改）：period={}", p);
            return;
        }
        log.info("[考勤审批] 历史导入审批单收敛为 APPROVED：period={}，原状态={}", p, entity.getStatus());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteHistoryApproval(String period) {
        if (StringUtils.isBlank(period)) {
            return;
        }
        int deleted = approvalMapper.delete(new LambdaQueryWrapper<AttendanceApproval>()
            .eq(AttendanceApproval::getPeriod, period.trim())
            .eq(AttendanceApproval::getStatus, AttendanceApproval.STATUS_APPROVED)
            .isNull(AttendanceApproval::getProcessInstanceId));
        log.info("[考勤审批] 历史导入审批单清理：period={}, 删除 {} 张", period, deleted);
    }

    // ==================== 算薪卡点查询（PeopleAttendanceApprovalQueryPort） ====================

    @Override
    public AttendanceApprovalStatusDTO getApprovalStatus(String period) {
        AttendanceApprovalStatusDTO dto = new AttendanceApprovalStatusDTO();
        dto.setPeriod(period);
        dto.setDataExists(countByPeriod(period) > 0);
        AttendanceApproval entity = selectByPeriod(period);
        dto.setStatus(entity == null ? null : entity.getStatus());
        return dto;
    }

    // ==================== 期间锁定（提交后禁止手工改数） ====================

    @Override
    public boolean isPeriodLocked(String period) {
        if (StringUtils.isBlank(period)) {
            return false;
        }
        AttendanceApproval entity = selectByPeriod(period.trim());
        return entity != null
            && (AttendanceApproval.STATUS_SUBMITTED.equals(entity.getStatus())
                || AttendanceApproval.STATUS_APPROVED.equals(entity.getStatus()));
    }

    @Override
    public Set<String> lockedPeriods(Collection<String> periods) {
        if (periods == null || periods.isEmpty()) {
            return Set.of();
        }
        return approvalMapper.selectList(new LambdaQueryWrapper<AttendanceApproval>()
                .in(AttendanceApproval::getPeriod, periods)
                .in(AttendanceApproval::getStatus, List.of(
                    AttendanceApproval.STATUS_SUBMITTED, AttendanceApproval.STATUS_APPROVED)))
            .stream()
            .map(AttendanceApproval::getPeriod)
            .collect(Collectors.toSet());
    }

    // ==================== 内部方法 ====================

    /**
     * 聚合异常考勤快照 JSON。
     * 异常口径：迟到次数/迟到分钟/缺卡次数/旷工天数/请假天数 任一 >0；
     * 正常行免审。快照定格提交时点，总监审阅内容不随后续数据变动漂移。
     */
    private String buildSnapshotJson(YearMonth month, Long approvalId) {
        LocalDate monthStart = month.atDay(1);
        List<AttendanceRecord> rows = attendanceMapper.selectList(new LambdaQueryWrapper<AttendanceRecord>()
            .eq(AttendanceRecord::getAttendMonth, monthStart));
        if (rows.isEmpty()) {
            throw new ServiceException("该期间无考勤数据，无法提交审批");
        }
        List<AttendanceRecord> abnormal = rows.stream().filter(r ->
            pos(r.getLateCount()) || pos(r.getLateMinutes()) || pos(r.getMissingCardCount())
                || pos(r.getAbsentDays()) || pos(r.getLeaveDays())).toList();

        Map<Long, Employee> empMap = abnormal.isEmpty() ? Map.of()
            : employeeMapper.selectByIds(abnormal.stream().map(AttendanceRecord::getEmployeeId).distinct().toList())
            .stream().collect(Collectors.toMap(Employee::getEmployeeId, Function.identity(), (a, b) -> a));

        List<AttendanceApprovalVO.AbnormalRow> voRows = abnormal.stream().map(r -> {
            AttendanceApprovalVO.AbnormalRow row = new AttendanceApprovalVO.AbnormalRow();
            row.setEmployeeId(r.getEmployeeId());
            Employee emp = empMap.get(r.getEmployeeId());
            row.setEmployeeCode(emp == null ? null : emp.getEmployeeCode());
            row.setEmployeeName(emp == null ? null : emp.getEmployeeName());
            row.setAttendMonth(r.getAttendMonth() == null ? null : r.getAttendMonth().toString());
            row.setLateCount(r.getLateCount() == null ? BigDecimal.ZERO : BigDecimal.valueOf(r.getLateCount()));
            row.setLateMinutes(r.getLateMinutes() == null ? BigDecimal.ZERO : BigDecimal.valueOf(r.getLateMinutes()));
            row.setMissingCardCount(r.getMissingCardCount() == null ? BigDecimal.ZERO : BigDecimal.valueOf(r.getMissingCardCount()));
            row.setAbsentDays(r.getAbsentDays() == null ? BigDecimal.ZERO : r.getAbsentDays());
            row.setLeaveDays(r.getLeaveDays() == null ? BigDecimal.ZERO : r.getLeaveDays());
            row.setRemark(r.getRemark());
            return row;
        }).toList();

        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("totalCount", rows.size());
        snapshot.put("abnormalCount", voRows.size());
        snapshot.put("abnormalLeaveDays", voRows.stream()
            .map(AttendanceApprovalVO.AbnormalRow::getLeaveDays)
            .filter(java.util.Objects::nonNull)
            .reduce(BigDecimal.ZERO, BigDecimal::add));
        snapshot.put("rows", voRows);
        return JSON.writeValueAsString(snapshot);
    }

    /** 发起流程命令（对齐 payroll_batch 口径；variables 必须可变，适配器会写入流程变量） */
    private ApprovalStartCmd buildStartCmd(AttendanceApproval entity, Long operatorId) {
        ApprovalStartCmd cmd = ApprovalStartCmd.of(entity.getPeriod(),
            "考勤月度审批｜" + entity.getPeriod());
        cmd.setHandler(String.valueOf(operatorId));
        Map<String, Object> variables = new HashMap<>(2);
        variables.put("ignore", true);
        cmd.setVariables(variables);
        return cmd;
    }

    /** VO 装配（快照 JSON → 异常明细） */
    private AttendanceApprovalVO toVo(AttendanceApproval entity, String period) {
        AttendanceApprovalVO vo = new AttendanceApprovalVO();
        vo.setPeriod(period);
        if (entity == null) {
            return vo;
        }
        vo.setId(entity.getId());
        vo.setStatus(entity.getStatus());
        vo.setSubmitBy(entity.getSubmitBy());
        vo.setSubmitTime(entity.getSubmitTime());
        vo.setApproveBy(entity.getApproveBy());
        vo.setApproveTime(entity.getApproveTime());
        vo.setRejectReason(entity.getRejectReason());
        vo.setProcessInstanceId(entity.getProcessInstanceId());
        if (StringUtils.isNotBlank(entity.getSnapshot())) {
            try {
                JsonNode node = JSON.readTree(entity.getSnapshot());
                vo.setTotalCount(node.path("totalCount").asInt());
                vo.setAbnormalCount(node.path("abnormalCount").asInt());
                vo.setAbnormalLeaveDays(dec(node.path("abnormalLeaveDays")));
                List<AttendanceApprovalVO.AbnormalRow> rows = new ArrayList<>();
                for (JsonNode r : node.path("rows")) {
                    AttendanceApprovalVO.AbnormalRow row = new AttendanceApprovalVO.AbnormalRow();
                    row.setEmployeeId(r.path("employeeId").isNumber() ? r.path("employeeId").asLong() : null);
                    row.setEmployeeCode(textOrNull(r.path("employeeCode")));
                    row.setEmployeeName(textOrNull(r.path("employeeName")));
                    row.setAttendMonth(textOrNull(r.path("attendMonth")));
                    row.setLateCount(dec(r.path("lateCount")));
                    row.setLateMinutes(dec(r.path("lateMinutes")));
                    row.setMissingCardCount(dec(r.path("missingCardCount")));
                    row.setAbsentDays(dec(r.path("absentDays")));
                    row.setLeaveDays(dec(r.path("leaveDays")));
                    row.setRemark(textOrNull(r.path("remark")));
                    rows.add(row);
                }
                vo.setAbnormalRows(rows);
            } catch (Exception e) {
                log.warn("[考勤审批] 快照解析失败：id={}", entity.getId(), e);
            }
        }
        return vo;
    }

    private long countByPeriod(String period) {
        try {
            YearMonth month = YearMonth.parse(period.trim());
            return attendanceMapper.selectCount(new LambdaQueryWrapper<AttendanceRecord>()
                .eq(AttendanceRecord::getAttendMonth, month.atDay(1)));
        } catch (DateTimeParseException e) {
            return 0;
        }
    }

    private AttendanceApproval selectByPeriod(String period) {
        return approvalMapper.selectOne(new LambdaQueryWrapper<AttendanceApproval>()
            .eq(AttendanceApproval::getPeriod, period)
            .last("LIMIT 1"));
    }

    private AttendanceApproval requireById(Long id) {
        AttendanceApproval entity = approvalMapper.selectById(id);
        if (entity == null) {
            throw new ServiceException("审批单不存在，id={}", id);
        }
        return entity;
    }

    private void validatePeriod(String period) {
        if (StringUtils.isBlank(period)) {
            throw new ServiceException("归属期间不能为空");
        }
        try {
            YearMonth.parse(period.trim());
        } catch (DateTimeParseException e) {
            throw new ServiceException("归属期间格式不正确，应为 YYYY-MM：{}", period);
        }
    }

    /** 数值列 >0 判定（null 视为 0） */
    private boolean pos(Number v) {
        return v != null && v.doubleValue() > 0;
    }

    private String textOrNull(JsonNode node) {
        return node.isMissingNode() || node.isNull() ? null : node.asText();
    }

    private BigDecimal dec(JsonNode node) {
        if (node.isMissingNode() || node.isNull() || !node.isNumber()) {
            return null;
        }
        try {
            return new BigDecimal(node.asText());
        } catch (NumberFormatException e) {
            return null;
        }
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
