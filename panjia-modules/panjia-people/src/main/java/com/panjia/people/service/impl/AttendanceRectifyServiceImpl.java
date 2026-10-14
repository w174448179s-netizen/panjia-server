package com.panjia.people.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.dromara.common.core.domain.PageResult;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.panjia.contracts.constant.BizType;
import com.panjia.contracts.port.ApprovalPort;
import com.panjia.contracts.port.ApprovalStartCmd;
import com.panjia.people.domain.AttendanceRecord;
import com.panjia.people.domain.AttendanceRectify;
import com.panjia.people.domain.Employee;
import com.panjia.people.dto.AttendanceRectifyQuery;
import com.panjia.people.dto.AttendanceRectifySubmitDTO;
import com.panjia.people.dto.AttendanceRectifyVO;
import com.panjia.people.dto.RectifyChangeItem;
import com.panjia.people.mapper.AttendanceRectifyMapper;
import com.panjia.people.mapper.AttendanceRecordMapper;
import com.panjia.people.mapper.EmployeeMapper;
import com.panjia.people.service.AttendanceApprovalService;
import com.panjia.people.service.AttendanceRectifyService;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 考勤修改申请服务。
 * <p>
 * 生命周期：经纪人提交（发起 Warm-Flow attendance_rectify，首节点自动办理）→
 * 人事在「我的待办」审批 → finish 回写月考勤并使月度审批失效回退；back 驳回即终态
 * （清理回退到提交节点的流程实例），经纪人可重新发起新单。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AttendanceRectifyServiceImpl implements AttendanceRectifyService {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final DateTimeFormatter PERIOD_FMT = DateTimeFormatter.ofPattern("yyyy-MM");

    /** 可修改字段白名单：field → 中文名 */
    private static final Map<String, String> FIELD_NAMES = Map.of(
        "attendDays", "出勤天数",
        "restDays", "休息天数",
        "lateCount", "迟到次数",
        "lateMinutes", "迟到分钟",
        "missingCardCount", "缺卡次数",
        "absentDays", "旷工天数",
        "leaveDays", "请假天数",
        "remark", "备注");

    /** 整数字段（非负） */
    private static final Set<String> INT_FIELDS = Set.of("lateCount", "lateMinutes", "missingCardCount");

    private final AttendanceRectifyMapper rectifyMapper;
    private final AttendanceRecordMapper attendanceMapper;
    private final EmployeeMapper employeeMapper;
    private final AttendanceApprovalService approvalService;
    private final ApprovalPort approvalPort;

    // ==================== 本人查询 ====================

    @Override
    public PageResult<AttendanceRectifyVO> myPage(Long loginUserId, AttendanceRectifyQuery query, PageQuery pageQuery) {
        Long myEmployeeId = findEmployeeIdByUserId(loginUserId);
        if (myEmployeeId == null) {
            // 无员工档案的账号（如纯系统管理员）：空页
            return PageResult.build(List.of(), 0L);
        }
        LocalDate monthStart = StringUtils.isBlank(query.getMonthStart()) ? null : parseMonth(query.getMonthStart());
        LocalDate monthEnd = StringUtils.isBlank(query.getMonthEnd()) ? null : parseMonth(query.getMonthEnd());
        LambdaQueryWrapper<AttendanceRectify> wrapper = new LambdaQueryWrapper<AttendanceRectify>()
            .eq(AttendanceRectify::getEmployeeId, myEmployeeId)
            .ge(monthStart != null, AttendanceRectify::getAttendMonth, monthStart)
            .le(monthEnd != null, AttendanceRectify::getAttendMonth, monthEnd)
            .eq(StringUtils.isNotBlank(query.getStatus()), AttendanceRectify::getStatus, query.getStatus())
            .orderByDesc(AttendanceRectify::getAttendMonth)
            .orderByDesc(AttendanceRectify::getId);
        Page<AttendanceRectify> page = rectifyMapper.selectPage(pageQuery.build(), wrapper);
        List<AttendanceRectifyVO> vos = page.getRecords().stream().map(this::toVO).toList();
        enrich(vos);
        return PageResult.build(vos, page.getTotal());
    }

    // ==================== 管理端查询 ====================

    @Override
    public PageResult<AttendanceRectifyVO> pageManage(AttendanceRectifyQuery query, PageQuery pageQuery) {
        LocalDate monthStart = StringUtils.isBlank(query.getMonthStart()) ? null : parseMonth(query.getMonthStart());
        LocalDate monthEnd = StringUtils.isBlank(query.getMonthEnd()) ? null : parseMonth(query.getMonthEnd());
        LambdaQueryWrapper<AttendanceRectify> wrapper = new LambdaQueryWrapper<AttendanceRectify>()
            .ge(monthStart != null, AttendanceRectify::getAttendMonth, monthStart)
            .le(monthEnd != null, AttendanceRectify::getAttendMonth, monthEnd)
            .eq(StringUtils.isNotBlank(query.getStatus()), AttendanceRectify::getStatus, query.getStatus());
        // 姓名模糊：先定位员工 ID 集合（对齐 AttendanceServiceImpl.pageManage 口径）
        if (StringUtils.isNotBlank(query.getEmployeeName())) {
            List<Long> empIds = employeeMapper.selectList(new LambdaQueryWrapper<Employee>()
                    .select(Employee::getEmployeeId)
                    .like(Employee::getEmployeeName, query.getEmployeeName().trim()))
                .stream().map(Employee::getEmployeeId).toList();
            if (empIds.isEmpty()) {
                return PageResult.build(List.of(), 0L);
            }
            wrapper.in(AttendanceRectify::getEmployeeId, empIds);
        }
        wrapper.orderByDesc(AttendanceRectify::getAttendMonth).orderByDesc(AttendanceRectify::getId);
        Page<AttendanceRectify> page = rectifyMapper.selectPage(pageQuery.build(), wrapper);
        List<AttendanceRectifyVO> vos = page.getRecords().stream().map(this::toVO).toList();
        enrich(vos);
        return PageResult.build(vos, page.getTotal());
    }

    // ==================== 提交申请 ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long submit(Long loginUserId, AttendanceRectifySubmitDTO dto) {
        Long employeeId = findEmployeeIdByUserId(loginUserId);
        if (employeeId == null) {
            throw new ServiceException("当前账号未绑定员工档案，无法发起考勤修改申请");
        }
        LocalDate monthStart = parseMonth(dto.getAttendMonth());
        AttendanceRecord record = attendanceMapper.selectOne(new LambdaQueryWrapper<AttendanceRecord>()
            .eq(AttendanceRecord::getEmployeeId, employeeId)
            .eq(AttendanceRecord::getAttendMonth, monthStart)
            .last("limit 1"));
        if (record == null) {
            throw new ServiceException("该月暂无考勤记录，无法发起修改申请");
        }
        String period = monthStart.format(PERIOD_FMT);
        if (approvalService.isPeriodLocked(period)) {
            throw new ServiceException("该月考勤已提交审批（或已通过），无法发起修改申请，请联系人事");
        }
        List<RectifyChangeItem> items = validateAndSnapshot(record, dto.getChanges());
        validateInvolveDates(dto.getInvolveDates());
        // 在途预检（友好提示；并发兜底靠 uk_rectify_inflight 部分唯一索引）
        boolean inflight = rectifyMapper.exists(new LambdaQueryWrapper<AttendanceRectify>()
            .eq(AttendanceRectify::getEmployeeId, employeeId)
            .eq(AttendanceRectify::getAttendMonth, monthStart)
            .eq(AttendanceRectify::getStatus, AttendanceRectify.STATUS_SUBMITTED));
        if (inflight) {
            throw new ServiceException("该月已有在途的修改申请，请等待人事审批结果");
        }

        AttendanceRectify entity = new AttendanceRectify();
        entity.setEmployeeId(employeeId);
        entity.setAttendMonth(monthStart);
        entity.setChangesJson(JSON.writeValueAsString(items));
        entity.setInvolveDates(StringUtils.trimToNull(dto.getInvolveDates()));
        entity.setReason(dto.getReason().trim());
        entity.setOssIds(dto.getOssIds().trim());
        entity.setStatus(AttendanceRectify.STATUS_SUBMITTED);
        entity.setSubmitBy(loginUserId);
        entity.setSubmitTime(LocalDateTime.now());
        try {
            rectifyMapper.insert(entity);
        } catch (DuplicateKeyException e) {
            throw new ServiceException("该月已有在途的修改申请，请等待人事审批结果");
        }

        Employee me = employeeMapper.selectOne(new LambdaQueryWrapper<Employee>()
            .eq(Employee::getEmployeeId, employeeId)
            .select(Employee::getEmployeeCode, Employee::getEmployeeName)
            .last("limit 1"));
        ApprovalStartCmd cmd = ApprovalStartCmd.of(String.valueOf(entity.getId()),
            "考勤修改审批｜" + (me == null ? "" : me.getEmployeeName()) + "｜" + period);
        cmd.setHandler(String.valueOf(loginUserId));
        // variables 必须可变（对齐考勤月度审批口径），适配器会写入流程变量
        Map<String, Object> variables = new HashMap<>(2);
        variables.put("ignore", true);
        cmd.setVariables(variables);
        boolean ok;
        try {
            ok = approvalPort.startAndCompleteFirst(BizType.ATTENDANCE_RECTIFY, entity.getId(), cmd);
        } catch (Exception e) {
            log.error("[考勤修改] 流程发起异常：rectifyId={}", entity.getId(), e);
            throw new ServiceException("考勤修改审批流程发起失败：{}", e.getMessage());
        }
        if (!ok) {
            throw new ServiceException("考勤修改审批流程发起失败");
        }
        Long instanceId = approvalPort.instanceId(BizType.ATTENDANCE_RECTIFY, entity.getId());
        entity.setProcessInstanceId(instanceId == null ? null : String.valueOf(instanceId));
        rectifyMapper.updateById(entity);
        log.info("[考勤修改] 提交申请：rectifyId={}, employeeId={}, period={}, operatorId={}",
            entity.getId(), employeeId, period, loginUserId);
        return entity.getId();
    }

    // ==================== 审批详情 ====================

    @Override
    public AttendanceRectifyVO getByBizId(Long loginUserId, Long id) {
        AttendanceRectify entity = rectifyMapper.selectById(id);
        if (entity == null) {
            throw new ServiceException("考勤修改申请不存在，id={}", id);
        }
        // 非人事/总监（无 people:attendance:list）仅可查看本人申请，防止越权查看他人记录
        if (!StpUtil.hasPermission("people:attendance:list")) {
            Long myEmployeeId = findEmployeeIdByUserId(loginUserId);
            if (myEmployeeId == null || !myEmployeeId.equals(entity.getEmployeeId())) {
                throw new ServiceException("无权查看该考勤修改申请");
            }
        }
        AttendanceRectifyVO vo = toVO(entity);
        enrich(List.of(vo));
        return vo;
    }

    // ==================== 工作流事件回写 ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void handleWorkflowEvent(Long bizId, String status, String handler, String message) {
        AttendanceRectify entity = rectifyMapper.selectById(bizId);
        if (entity == null) {
            log.warn("[考勤修改] 工作流回调申请单不存在，忽略：id={}, status={}", bizId, status);
            return;
        }
        Long handlerId = parseHandlerId(handler);
        switch (status == null ? "" : status) {
            case "finish" -> {
                if (!AttendanceRectify.STATUS_SUBMITTED.equals(entity.getStatus())) {
                    log.warn("[考勤修改] 非在途单据收到 finish，幂等跳过：id={}, status={}", bizId, entity.getStatus());
                    return;
                }
                // 先生效修改（失败抛异常使审批整体回滚），再回写单据状态
                applyChanges(entity);
                entity.setStatus(AttendanceRectify.STATUS_APPROVED);
                entity.setApproveBy(handlerId);
                entity.setApproveTime(LocalDateTime.now());
                if (rectifyMapper.updateById(entity) == 0) {
                    throw new ServiceException("考勤修改申请回写失败（并发修改），请重试");
                }
            }
            case "back" -> {
                entity.setStatus(AttendanceRectify.STATUS_REJECTED);
                entity.setApproveBy(handlerId);
                entity.setApproveTime(LocalDateTime.now());
                entity.setRejectReason(StringUtils.isBlank(message) ? "人事驳回" : message);
                if (rectifyMapper.updateById(entity) == 0) {
                    log.warn("[考勤修改] 驳回回写失败（并发修改）：id={}", bizId);
                }
                // 驳回后实例回退在「提交申请」节点：撤销实例清理悬挂待办（驳回即终态，重提走新单）
                try {
                    approvalPort.cancel(BizType.ATTENDANCE_RECTIFY, bizId);
                } catch (Exception e) {
                    log.warn("[考勤修改] 驳回后撤销流程实例失败（不影响驳回结果）：id={}", bizId, e);
                }
            }
            case "cancel", "invalid", "termination" -> {
                // 幂等：仅收敛在途单据（驳回后撤销实例触发的 cancel 事件会走到这里，状态已是 REJECTED 则跳过）
                if (!AttendanceRectify.STATUS_SUBMITTED.equals(entity.getStatus())) {
                    return;
                }
                entity.setStatus(AttendanceRectify.STATUS_CANCELED);
                if (rectifyMapper.updateById(entity) == 0) {
                    log.warn("[考勤修改] 取消回写失败（并发修改）：id={}", bizId);
                }
            }
            default -> {
                log.info("[考勤修改] 忽略流程状态：id={}, status={}", bizId, status);
                return;
            }
        }
        log.info("[考勤修改] 工作流回写：id={}, status={}, handler={}", bizId, status, handler);
    }

    // ==================== 内部方法 ====================

    /** 审批通过生效：把修改项应用到月考勤记录，并使当月考勤月度审批失效回退（同事务） */
    private void applyChanges(AttendanceRectify entity) {
        List<RectifyChangeItem> items = JSON.readValue(entity.getChangesJson(), new TypeReference<>() {});
        AttendanceRecord record = attendanceMapper.selectOne(new LambdaQueryWrapper<AttendanceRecord>()
            .eq(AttendanceRecord::getEmployeeId, entity.getEmployeeId())
            .eq(AttendanceRecord::getAttendMonth, entity.getAttendMonth())
            .last("limit 1"));
        if (record == null) {
            throw new ServiceException("该月考勤记录不存在，修改无法生效");
        }
        for (RectifyChangeItem item : items) {
            applyField(record, item.getField(), item.getNewValue());
        }
        if (attendanceMapper.updateById(record) == 0) {
            throw new ServiceException("考勤记录已被他人修改，生效失败，请重新审批");
        }
        // 数据已变：在途/已通过的月度审批失效回待提交，人事重新统一提交总监
        approvalService.invalidateOnDataChange(entity.getAttendMonth().format(PERIOD_FMT));
        log.info("[考勤修改] 修改已生效：rectifyId={}, employeeId={}, month={}",
            entity.getId(), entity.getEmployeeId(), entity.getAttendMonth());
    }

    /** 按白名单字段把新值写入考勤记录（与提交时同一套类型解析） */
    private void applyField(AttendanceRecord record, String field, String newValue) {
        switch (field == null ? "" : field) {
            case "attendDays" -> record.setAttendDays(parseDecimal(field, newValue));
            case "restDays" -> record.setRestDays(parseDecimal(field, newValue));
            case "absentDays" -> record.setAbsentDays(parseDecimal(field, newValue));
            case "leaveDays" -> record.setLeaveDays(parseDecimal(field, newValue));
            case "lateCount" -> record.setLateCount(parseInt(field, newValue));
            case "lateMinutes" -> record.setLateMinutes(parseInt(field, newValue));
            case "missingCardCount" -> record.setMissingCardCount(parseInt(field, newValue));
            case "remark" -> record.setRemark(StringUtils.trimToNull(newValue));
            default -> throw new ServiceException("不支持的修改字段：{}", field);
        }
    }

    /** 校验修改项并快照原值：白名单/类型/非负/去重/新旧值差异 */
    private List<RectifyChangeItem> validateAndSnapshot(AttendanceRecord record, List<RectifyChangeItem> changes) {
        Set<String> seen = new HashSet<>();
        for (RectifyChangeItem item : changes) {
            String field = item.getField();
            String fieldName = FIELD_NAMES.get(field);
            if (fieldName == null) {
                throw new ServiceException("不支持的修改字段：{}", field);
            }
            if (!seen.add(field)) {
                throw new ServiceException("字段【{}】重复修改", fieldName);
            }
            String oldValue = currentValue(record, field);
            String newValue = StringUtils.trimToEmpty(item.getNewValue());
            if ("remark".equals(field)) {
                if (newValue.length() > 512) {
                    throw new ServiceException("【备注】长度不能超过 512 个字符");
                }
            } else {
                if (newValue.isEmpty()) {
                    throw new ServiceException("【{}】新值不能为空", fieldName);
                }
                // 统一按 BigDecimal 口径比较，避免 1 与 1.0 误判为差异；原值为空（可空字段未填）则必为差异
                BigDecimal newNum = parseDecimal(field, newValue);
                if (!oldValue.isEmpty() && newNum.compareTo(parseDecimal(field, oldValue)) == 0) {
                    throw new ServiceException("【{}】新值与原值相同", fieldName);
                }
                if (INT_FIELDS.contains(field)) {
                    parseInt(field, newValue);
                }
            }
            item.setFieldName(fieldName);
            item.setOldValue(oldValue);
            item.setNewValue(newValue);
        }
        return changes;
    }

    /** 考勤记录当前值（字符串形式，null → ""） */
    private String currentValue(AttendanceRecord record, String field) {
        Object v = switch (field) {
            case "attendDays" -> record.getAttendDays();
            case "restDays" -> record.getRestDays();
            case "absentDays" -> record.getAbsentDays();
            case "leaveDays" -> record.getLeaveDays();
            case "lateCount" -> record.getLateCount();
            case "lateMinutes" -> record.getLateMinutes();
            case "missingCardCount" -> record.getMissingCardCount();
            case "remark" -> record.getRemark();
            default -> null;
        };
        return v == null ? "" : v.toString();
    }

    private Integer parseInt(String field, String value) {
        try {
            int v = Integer.parseInt(StringUtils.trimToEmpty(value));
            if (v < 0) {
                throw new ServiceException("【{}】不能为负数", FIELD_NAMES.get(field));
            }
            return v;
        } catch (NumberFormatException e) {
            throw new ServiceException("【{}】必须为非负整数", FIELD_NAMES.get(field));
        }
    }

    private BigDecimal parseDecimal(String field, String value) {
        try {
            BigDecimal v = new BigDecimal(StringUtils.trimToEmpty(value));
            if (v.signum() < 0) {
                throw new ServiceException("【{}】不能为负数", FIELD_NAMES.get(field));
            }
            return v;
        } catch (NumberFormatException e) {
            throw new ServiceException("【{}】必须为数字", FIELD_NAMES.get(field));
        }
    }

    // ==================== 导入卡点查询（PeopleAttendanceRectifyQueryPort） ====================

    @Override
    public boolean hasEffectiveRectify(String period) {
        String t = StringUtils.trimToNull(period);
        if (t == null) {
            return false;
        }
        LocalDate monthStart;
        try {
            monthStart = t.length() == 7 ? YearMonth.parse(t).atDay(1) : LocalDate.parse(t).withDayOfMonth(1);
        } catch (DateTimeParseException e) {
            return false;
        }
        // 待审批/已通过视为有效：导入（同人同月覆盖写）会冲掉申请依据或已生效的修改结果
        return rectifyMapper.exists(new LambdaQueryWrapper<AttendanceRectify>()
            .eq(AttendanceRectify::getAttendMonth, monthStart)
            .in(AttendanceRectify::getStatus,
                List.of(AttendanceRectify.STATUS_SUBMITTED, AttendanceRectify.STATUS_APPROVED)));
    }

    /** 涉及日期逐枚校验格式（yyyy-MM-dd，逗号分隔；可空） */
    private void validateInvolveDates(String involveDates) {
        if (StringUtils.isBlank(involveDates)) {
            return;
        }
        for (String d : involveDates.split(",")) {
            try {
                LocalDate.parse(d.trim());
            } catch (DateTimeParseException e) {
                throw new ServiceException("涉及日期格式不正确：{}", d.trim());
            }
        }
    }

    /** 解析考勤月份（yyyy-MM 或 yyyy-MM-dd，归一为当月 1 日） */
    private LocalDate parseMonth(String text) {
        String t = StringUtils.trimToEmpty(text);
        try {
            if (t.length() == 7) {
                return YearMonth.parse(t).atDay(1);
            }
            return LocalDate.parse(t).withDayOfMonth(1);
        } catch (DateTimeParseException e) {
            throw new ServiceException("考勤月份格式不正确：{}", text);
        }
    }

    private Long findEmployeeIdByUserId(Long loginUserId) {
        if (loginUserId == null) {
            return null;
        }
        Employee employee = employeeMapper.selectOne(new LambdaQueryWrapper<Employee>()
            .eq(Employee::getUserId, loginUserId)
            .select(Employee::getEmployeeId)
            .last("limit 1"));
        return employee == null ? null : employee.getEmployeeId();
    }

    private AttendanceRectifyVO toVO(AttendanceRectify entity) {
        AttendanceRectifyVO vo = new AttendanceRectifyVO();
        vo.setId(entity.getId());
        vo.setEmployeeId(entity.getEmployeeId());
        vo.setAttendMonth(entity.getAttendMonth());
        vo.setChanges(JSON.readValue(entity.getChangesJson(), new TypeReference<>() {}));
        vo.setInvolveDates(entity.getInvolveDates());
        vo.setReason(entity.getReason());
        vo.setOssIds(entity.getOssIds());
        vo.setStatus(entity.getStatus());
        vo.setSubmitBy(entity.getSubmitBy());
        vo.setSubmitTime(entity.getSubmitTime());
        vo.setApproveBy(entity.getApproveBy());
        vo.setApproveTime(entity.getApproveTime());
        vo.setRejectReason(entity.getRejectReason());
        vo.setCreateTime(entity.getCreateTime());
        return vo;
    }

    /** 批量补员工工号/姓名展示字段 */
    private void enrich(List<AttendanceRectifyVO> vos) {
        Set<Long> employeeIds = vos.stream().map(AttendanceRectifyVO::getEmployeeId).collect(Collectors.toSet());
        if (employeeIds.isEmpty()) {
            return;
        }
        Map<Long, Employee> empMap = employeeMapper.selectList(new LambdaQueryWrapper<Employee>()
                .in(Employee::getEmployeeId, employeeIds)
                .select(Employee::getEmployeeId, Employee::getEmployeeCode, Employee::getEmployeeName))
            .stream().collect(Collectors.toMap(Employee::getEmployeeId, Function.identity()));
        for (AttendanceRectifyVO vo : vos) {
            Employee e = empMap.get(vo.getEmployeeId());
            if (e != null) {
                vo.setEmployeeCode(e.getEmployeeCode());
                vo.setEmployeeName(e.getEmployeeName());
            }
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
