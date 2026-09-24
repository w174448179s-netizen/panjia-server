package com.panjia.people.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.panjia.contracts.constant.BizType;
import com.panjia.contracts.dto.PointsRuleDTO;
import com.panjia.contracts.dto.ScoreApprovalStatusDTO;
import com.panjia.contracts.port.ApprovalAction;
import com.panjia.contracts.port.ApprovalPort;
import com.panjia.contracts.port.ApprovalStartCmd;
import com.panjia.people.domain.Employee;
import com.panjia.people.domain.PerformanceScore;
import com.panjia.people.domain.ScoreApproval;
import com.panjia.people.dto.ScoreApprovalVO;
import com.panjia.people.mapper.EmployeeMapper;
import com.panjia.people.mapper.PerformanceScoreMapper;
import com.panjia.people.mapper.ScoreApprovalMapper;
import com.panjia.people.service.ScoreApprovalService;
import com.panjia.people.service.ScoreGradePolicy;
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
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 积分审批服务实现（warm-flow：score_approval 积分月度审批）。
 * <p>
 * 流程：score_start → score_submit（提交积分，人事）→ score_review（总监审核，
 * 节点 ext AutoApproval 24h 超时自动通过）→ score_end；总监驳回回 score_submit。
 * <p>
 * 提交时按"扣点行"聚合快照（绩效等级 B/C 的行——B 扣 2%、C 扣 4%，
 * 均影响提成；A 级不扣点免审），快照定格在审批单上，办理弹窗展示。
 * 状态回写唯一入口是工作流事件（handleWorkflowEvent），无业务直批路径。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ScoreApprovalServiceImpl implements ScoreApprovalService {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final ScoreApprovalMapper approvalMapper;
    private final PerformanceScoreMapper scoreMapper;
    private final EmployeeMapper employeeMapper;
    private final ApprovalPort approvalPort;
    private final ScoreGradePolicy scoreGradePolicy;

    @Override
    public ScoreApprovalVO getByPeriod(String period) {
        ScoreApprovalVO vo = toVo(selectByPeriod(period), period);
        // 该月是否有积分数据（算薪页创建批次前用于「无积分确认」提示）
        vo.setDataExists(countByPeriod(period) > 0);
        return vo;
    }

    @Override
    public ScoreApprovalVO getByBizId(Long bizId) {
        ScoreApproval entity = requireById(bizId);
        return toVo(entity, entity.getPeriod());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void submit(String period, Long operatorId) {
        validatePeriod(period);
        ScoreApproval entity = selectByPeriod(period);
        if (entity != null && ScoreApproval.STATUS_SUBMITTED.equals(entity.getStatus())) {
            throw new ServiceException("该期间积分审批正在流程中，请等待总监审核或超时自动通过");
        }
        if (entity == null) {
            entity = new ScoreApproval();
            entity.setPeriod(period);
            entity.setStatus(ScoreApproval.STATUS_DRAFT);
            approvalMapper.insert(entity);
        }

        // 扣点行快照（含"该期间有数据"校验）；驳回后重提也重新定格最新数据
        String snapshotJson = buildSnapshotJson(YearMonth.parse(period.trim()), entity.getId());

        boolean resubmit = ScoreApproval.STATUS_REJECTED.equals(entity.getStatus())
            && StringUtils.isNotBlank(entity.getProcessInstanceId());
        if (resubmit) {
            // 驳回后流程停在「提交积分」节点：办理该任务重新进入总监审核
            boolean ok = approvalPort.completeAsSys(BizType.SCORE_APPROVAL, entity.getId(),
                ApprovalAction.PASS, "重新提交");
            if (!ok) {
                throw new ServiceException("重新提交失败，流程任务不存在，请联系管理员");
            }
        } else {
            // 同一单据已有历史流程实例（已通过/已驳回且回调未回写实例ID）：
            // 先撤销旧实例再重新发起，否则引擎按 businessId 校验报「该单据已完成申请」
            if (approvalPort.instanceId(BizType.SCORE_APPROVAL, entity.getId()) != null) {
                approvalPort.cancel(BizType.SCORE_APPROVAL, entity.getId());
            }
            // 首次提交：发起流程并自动办理「提交积分」首节点
            ApprovalStartCmd cmd = buildStartCmd(entity, operatorId);
            boolean ok;
            try {
                ok = approvalPort.startAndCompleteFirst(BizType.SCORE_APPROVAL, entity.getId(), cmd);
            } catch (Exception e) {
                log.error("[积分审批] 流程发起异常：approvalId={}", entity.getId(), e);
                throw new ServiceException("积分审批流程发起失败：{}", e.getMessage());
            }
            if (!ok) {
                throw new ServiceException("积分审批流程发起失败");
            }
            Long instanceId = approvalPort.instanceId(BizType.SCORE_APPROVAL, entity.getId());
            entity.setProcessInstanceId(instanceId == null ? null : String.valueOf(instanceId));
        }

        entity.setSnapshot(snapshotJson);
        entity.setStatus(ScoreApproval.STATUS_SUBMITTED);
        entity.setSubmitBy(operatorId);
        entity.setSubmitTime(LocalDateTime.now());
        entity.setApproveBy(null);
        entity.setApproveTime(null);
        entity.setRejectReason(null);
        if (approvalMapper.updateById(entity) == 0) {
            throw new ServiceException("提交失败，审批单已被他人操作，请刷新后重试");
        }
        log.info("[积分审批] 提交审批：period={}, approvalId={}, operatorId={}, 重提={}",
            period, entity.getId(), operatorId, resubmit);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void handleWorkflowEvent(Long bizId, String status, String handler, String message) {
        ScoreApproval entity = approvalMapper.selectById(bizId);
        if (entity == null) {
            log.warn("[积分审批] 工作流回调审批单不存在，忽略：id={}, status={}", bizId, status);
            return;
        }
        Long handlerId = parseHandlerId(handler);
        switch (status == null ? "" : status) {
            case "finish" -> {
                entity.setStatus(ScoreApproval.STATUS_APPROVED);
                entity.setApproveBy(handlerId);
                entity.setApproveTime(LocalDateTime.now());
            }
            case "back" -> {
                entity.setStatus(ScoreApproval.STATUS_REJECTED);
                entity.setApproveBy(handlerId);
                entity.setApproveTime(LocalDateTime.now());
                entity.setRejectReason(StringUtils.isBlank(message) ? "总监驳回" : message);
            }
            case "cancel", "invalid", "termination" -> entity.setStatus(ScoreApproval.STATUS_DRAFT);
            default -> {
                log.info("[积分审批] 忽略流程状态：id={}, status={}", bizId, status);
                return;
            }
        }
        if (approvalMapper.updateById(entity) == 0) {
            log.warn("[积分审批] 工作流回写失败（并发修改）：id={}, status={}", bizId, status);
        }
        log.info("[积分审批] 工作流回写：period={}, status={}, handler={}", entity.getPeriod(), status, handler);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void invalidateOnDataChange(String period) {
        ScoreApproval entity = selectByPeriod(period);
        if (entity == null) {
            return;
        }
        String status = entity.getStatus();
        if (ScoreApproval.STATUS_SUBMITTED.equals(status)
            || ScoreApproval.STATUS_APPROVED.equals(status)) {
            // 在途/已完成流程均需撤销（数据已变，旧流程结果不再有效）：
            // 只回退审批单不撤实例，会让"我的已办/详情"残留旧流程状态，与实际不符
            approvalPort.cancel(BizType.SCORE_APPROVAL, entity.getId());
            // 校验实例确已清理，防止撤销失败产生僵尸实例
            if (approvalPort.instanceId(BizType.SCORE_APPROVAL, entity.getId()) != null) {
                throw new ServiceException("积分审批流程撤销失败，请稍后重试");
            }
            entity.setProcessInstanceId(null);
            entity.setStatus(ScoreApproval.STATUS_DRAFT);
            if (approvalMapper.updateById(entity) == 0) {
                log.warn("[积分审批] 失效审批单失败（并发修改）：period={}", period);
                return;
            }
            log.info("[积分审批] 积分数据变更，审批单失效回待提交：period={}，原状态={}", period, status);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void approveForHistory(String period) {
        if (StringUtils.isBlank(period)) {
            return;
        }
        String p = period.trim();
        ScoreApproval entity = selectByPeriod(p);
        if (entity == null) {
            entity = new ScoreApproval();
            entity.setPeriod(p);
            entity.setStatus(ScoreApproval.STATUS_APPROVED);
            entity.setSubmitTime(LocalDateTime.now());
            entity.setApproveTime(LocalDateTime.now());
            approvalMapper.insert(entity);
            // 快照在拿到单据 ID 后补写（历史补录无流程实例/操作人）
            entity.setSnapshot(buildSnapshotJson(YearMonth.parse(p), entity.getId()));
            if (approvalMapper.updateById(entity) == 0) {
                log.warn("[积分审批] 历史审批单快照补写失败（并发修改）：period={}", p);
            }
            log.info("[积分审批] 历史导入新增 APPROVED 审批单：period={}", p);
            return;
        }
        if (ScoreApproval.STATUS_APPROVED.equals(entity.getStatus())) {
            log.info("[积分审批] 历史导入审批单已为 APPROVED，跳过：period={}", p);
            return;
        }
        entity.setStatus(ScoreApproval.STATUS_APPROVED);
        entity.setApproveTime(LocalDateTime.now());
        entity.setSnapshot(buildSnapshotJson(YearMonth.parse(p), entity.getId()));
        if (approvalMapper.updateById(entity) == 0) {
            log.warn("[积分审批] 历史审批单收敛失败（并发修改）：period={}", p);
            return;
        }
        log.info("[积分审批] 历史导入审批单收敛为 APPROVED：period={}，原状态={}", p, entity.getStatus());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteHistoryApproval(String period) {
        if (StringUtils.isBlank(period)) {
            return;
        }
        int deleted = approvalMapper.delete(new LambdaQueryWrapper<ScoreApproval>()
            .eq(ScoreApproval::getPeriod, period.trim())
            .eq(ScoreApproval::getStatus, ScoreApproval.STATUS_APPROVED)
            .isNull(ScoreApproval::getProcessInstanceId));
        log.info("[积分审批] 历史导入审批单清理：period={}, 删除 {} 张", period, deleted);
    }

    // ==================== 算薪卡点查询（PeopleScoreApprovalQueryPort） ====================

    @Override
    public ScoreApprovalStatusDTO getApprovalStatus(String period) {
        ScoreApprovalStatusDTO dto = new ScoreApprovalStatusDTO();
        dto.setPeriod(period);
        dto.setDataExists(countByPeriod(period) > 0);
        ScoreApproval entity = selectByPeriod(period);
        dto.setStatus(entity == null ? null : entity.getStatus());
        return dto;
    }

    // ==================== 期间锁定 ====================

    @Override
    public boolean isPeriodLocked(String period) {
        if (StringUtils.isBlank(period)) {
            return false;
        }
        ScoreApproval entity = selectByPeriod(period.trim());
        return entity != null
            && (ScoreApproval.STATUS_SUBMITTED.equals(entity.getStatus())
                || ScoreApproval.STATUS_APPROVED.equals(entity.getStatus()));
    }

    @Override
    public Set<String> lockedPeriods(Collection<String> periods) {
        if (periods == null || periods.isEmpty()) {
            return Set.of();
        }
        return approvalMapper.selectList(new LambdaQueryWrapper<ScoreApproval>()
                .in(ScoreApproval::getPeriod, periods)
                .in(ScoreApproval::getStatus, List.of(
                    ScoreApproval.STATUS_SUBMITTED, ScoreApproval.STATUS_APPROVED)))
            .stream()
            .map(ScoreApproval::getPeriod)
            .collect(Collectors.toSet());
    }

    // ==================== 内部方法 ====================

    /**
     * 聚合扣点行快照 JSON。
     * 扣点口径：绩效等级 B（-2%）/ C（-4%）的行——均影响当月提成比例；
     * A 级（不扣点）与无等级（出勤 0 天，算薪默认 A）免审。
     * 快照定格提交时点，总监审阅内容不随后续数据变动漂移。
     */
    private String buildSnapshotJson(YearMonth month, Long approvalId) {
        LocalDate monthStart = month.atDay(1);
        List<PerformanceScore> rows = scoreMapper.selectList(new LambdaQueryWrapper<PerformanceScore>()
            .eq(PerformanceScore::getScoreMonth, monthStart));
        if (rows.isEmpty()) {
            throw new ServiceException("该期间无积分数据，无法提交审批");
        }
        // 派生字段（平均积分/等级/扣点/积分扣款）实时计算，不落库；规则整单只查一次
        PointsRuleDTO rule = scoreGradePolicy.currentRule();
        List<PerformanceScore> deductRows = rows.stream().filter(r -> {
            String grade = scoreGradePolicy.grade(rule, r.getTotalPoints(), r.getAttendDays());
            return "B".equals(grade) || "C".equals(grade);
        }).toList();

        // 晚提交行（lateSubmitCount > 0）
        List<PerformanceScore> lateRows = rows.stream()
            .filter(r -> r.getLateSubmitCount() != null && r.getLateSubmitCount() > 0)
            .toList();

        // 员工信息：合并扣点行 + 晚提交行的员工 ID 一次性查询
        Set<Long> empIds = new java.util.HashSet<>();
        deductRows.forEach(r -> empIds.add(r.getEmployeeId()));
        lateRows.forEach(r -> empIds.add(r.getEmployeeId()));
        Map<Long, Employee> empMap = empIds.isEmpty() ? Map.of()
            : employeeMapper.selectByIds(new ArrayList<>(empIds))
            .stream().collect(Collectors.toMap(Employee::getEmployeeId, Function.identity(), (a, b) -> a));

        List<ScoreApprovalVO.DeductRow> voRows = deductRows.stream().map(r -> {
            ScoreApprovalVO.DeductRow row = new ScoreApprovalVO.DeductRow();
            row.setEmployeeId(r.getEmployeeId());
            Employee emp = empMap.get(r.getEmployeeId());
            row.setEmployeeCode(emp == null ? null : emp.getEmployeeCode());
            row.setEmployeeName(emp == null ? null : emp.getEmployeeName());
            row.setScoreMonth(r.getScoreMonth() == null ? null : r.getScoreMonth().toString());
            row.setTotalPoints(r.getTotalPoints());
            row.setAttendDays(r.getAttendDays());
            BigDecimal avg = scoreGradePolicy.avgPoints(r.getTotalPoints(), r.getAttendDays());
            String grade = avg == null ? null : scoreGradePolicy.resolveGrade(rule, avg);
            row.setAvgPoints(avg);
            row.setGrade(grade);
            row.setDeductRate(scoreGradePolicy.deductOf(rule, grade));
            row.setLateSubmitCount(r.getLateSubmitCount());
            row.setPointsFee(r.getLateSubmitCount() != null && r.getLateSubmitCount() > 0
                ? scoreGradePolicy.lateFeeOf(rule, r.getLateSubmitCount()) : null);
            return row;
        }).toList();

        Map<String, Long> gradeCounts = rows.stream()
            .collect(Collectors.groupingBy(
                r -> StringUtils.defaultString(scoreGradePolicy.grade(rule, r.getTotalPoints(), r.getAttendDays())),
                Collectors.counting()));

        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("totalCount", rows.size());
        snapshot.put("gradeACount", gradeCounts.getOrDefault("A", 0L));
        snapshot.put("gradeBCount", gradeCounts.getOrDefault("B", 0L));
        snapshot.put("gradeCCount", gradeCounts.getOrDefault("C", 0L));
        snapshot.put("rows", voRows);

        // 晚提交扣款行快照（所有 lateSubmitCount > 0 的行，供总监核对豁免情况）
        List<ScoreApprovalVO.LateSubmitRow> lateVoRows = lateRows.stream().map(r -> {
            ScoreApprovalVO.LateSubmitRow row = new ScoreApprovalVO.LateSubmitRow();
            row.setEmployeeId(r.getEmployeeId());
            Employee emp = empMap.get(r.getEmployeeId());
            row.setEmployeeCode(emp == null ? null : emp.getEmployeeCode());
            row.setEmployeeName(emp == null ? null : emp.getEmployeeName());
            row.setScoreMonth(r.getScoreMonth() == null ? null : r.getScoreMonth().toString());
            row.setLateSubmitCount(r.getLateSubmitCount());
            row.setPointsFee(scoreGradePolicy.lateFeeOf(rule, r.getLateSubmitCount()));
            return row;
        }).toList();
        int lateTotalCount = lateRows.stream()
            .mapToInt(r -> r.getLateSubmitCount() == null ? 0 : r.getLateSubmitCount())
            .sum();
        BigDecimal lateTotalFee = lateVoRows.stream()
            .map(r -> r.getPointsFee() == null ? BigDecimal.ZERO : r.getPointsFee())
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        snapshot.put("lateSubmitTotalCount", lateTotalCount);
        snapshot.put("lateSubmitTotalFee", lateTotalFee);
        snapshot.put("lateSubmitRows", lateVoRows);

        return JSON.writeValueAsString(snapshot);
    }

    /** 发起流程命令（对齐考勤审批口径；variables 必须可变，适配器会写入流程变量） */
    private ApprovalStartCmd buildStartCmd(ScoreApproval entity, Long operatorId) {
        ApprovalStartCmd cmd = ApprovalStartCmd.of(entity.getPeriod(),
            "积分月度审批｜" + entity.getPeriod());
        cmd.setHandler(String.valueOf(operatorId));
        Map<String, Object> variables = new HashMap<>(2);
        variables.put("ignore", true);
        cmd.setVariables(variables);
        return cmd;
    }

    /** VO 装配（快照 JSON → 扣点明细） */
    private ScoreApprovalVO toVo(ScoreApproval entity, String period) {
        ScoreApprovalVO vo = new ScoreApprovalVO();
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
                vo.setGradeACount(node.path("gradeACount").asInt());
                vo.setGradeBCount(node.path("gradeBCount").asInt());
                vo.setGradeCCount(node.path("gradeCCount").asInt());
                List<ScoreApprovalVO.DeductRow> rows = new ArrayList<>();
                for (JsonNode r : node.path("rows")) {
                    ScoreApprovalVO.DeductRow row = new ScoreApprovalVO.DeductRow();
                    row.setEmployeeId(r.path("employeeId").isNumber() ? r.path("employeeId").asLong() : null);
                    row.setEmployeeCode(textOrNull(r.path("employeeCode")));
                    row.setEmployeeName(textOrNull(r.path("employeeName")));
                    row.setScoreMonth(textOrNull(r.path("scoreMonth")));
                    row.setTotalPoints(dec(r.path("totalPoints")));
                    row.setAttendDays(r.path("attendDays").isNumber() ? r.path("attendDays").asInt() : null);
                    row.setAvgPoints(dec(r.path("avgPoints")));
                    row.setGrade(textOrNull(r.path("grade")));
                    row.setDeductRate(dec(r.path("deductRate")));
                    row.setLateSubmitCount(r.path("lateSubmitCount").isNumber() ? r.path("lateSubmitCount").asInt() : null);
                    row.setPointsFee(dec(r.path("pointsFee")));
                    rows.add(row);
                }
                vo.setDeductRows(rows);

                // 晚提交扣款行快照
                vo.setLateSubmitTotalCount(node.path("lateSubmitTotalCount").asInt(0));
                vo.setLateSubmitTotalFee(dec(node.path("lateSubmitTotalFee")));
                List<ScoreApprovalVO.LateSubmitRow> lateRows = new ArrayList<>();
                for (JsonNode r : node.path("lateSubmitRows")) {
                    ScoreApprovalVO.LateSubmitRow row = new ScoreApprovalVO.LateSubmitRow();
                    row.setEmployeeId(r.path("employeeId").isNumber() ? r.path("employeeId").asLong() : null);
                    row.setEmployeeCode(textOrNull(r.path("employeeCode")));
                    row.setEmployeeName(textOrNull(r.path("employeeName")));
                    row.setScoreMonth(textOrNull(r.path("scoreMonth")));
                    row.setLateSubmitCount(r.path("lateSubmitCount").isNumber() ? r.path("lateSubmitCount").asInt() : null);
                    row.setPointsFee(dec(r.path("pointsFee")));
                    lateRows.add(row);
                }
                vo.setLateSubmitRows(lateRows);
            } catch (Exception e) {
                log.warn("[积分审批] 快照解析失败：id={}", entity.getId(), e);
            }
        }
        return vo;
    }

    private long countByPeriod(String period) {
        try {
            YearMonth month = YearMonth.parse(period.trim());
            return scoreMapper.selectCount(new LambdaQueryWrapper<PerformanceScore>()
                .eq(PerformanceScore::getScoreMonth, month.atDay(1)));
        } catch (DateTimeParseException e) {
            return 0;
        }
    }

    private ScoreApproval selectByPeriod(String period) {
        return approvalMapper.selectOne(new LambdaQueryWrapper<ScoreApproval>()
            .eq(ScoreApproval::getPeriod, period)
            .last("LIMIT 1"));
    }

    private ScoreApproval requireById(Long id) {
        ScoreApproval entity = approvalMapper.selectById(id);
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
