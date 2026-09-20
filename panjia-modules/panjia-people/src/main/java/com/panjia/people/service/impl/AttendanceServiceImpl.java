package com.panjia.people.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.panjia.contracts.dto.AttendanceMetricsDTO;
import com.panjia.contracts.dto.AttendanceSummarySyncDTO;
import com.panjia.contracts.port.PeopleAttendanceMetricsQueryPort;
import com.panjia.people.domain.AttendanceRecord;
import com.panjia.people.domain.Employee;
import com.panjia.people.dto.AttendanceQuery;
import com.panjia.people.dto.AttendanceSaveDTO;
import com.panjia.people.dto.AttendanceVO;
import com.panjia.people.mapper.AttendanceRecordMapper;
import com.panjia.people.mapper.EmployeeMapper;
import com.panjia.people.port.DeptPort;
import com.panjia.people.service.AttendanceApprovalService;
import com.panjia.people.service.AttendanceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.domain.PageResult;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 月考勤汇总服务实现（一员工一月一行）。
 * <p>
 * 粒度对齐钉钉《月度汇总》导入数据，服务薪酬扣款。
 * 本人查询的数据隔离在本类内闭合：{@link #pageMy}/{@link #getMy} 的员工身份
 * 只来自登录系统用户经 pj_people_employee.user_id 的映射，不接受请求参数。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AttendanceServiceImpl implements AttendanceService, PeopleAttendanceMetricsQueryPort {

    /** 数据来源：人工登记 */
    private static final String DATA_SOURCE_MANUAL = "MANUAL";

    /** 数据来源：钉钉《月度汇总》导入同步 */
    private static final String DATA_SOURCE_DINGTALK = "DINGTALK";

    private final AttendanceRecordMapper attendanceMapper;
    private final EmployeeMapper employeeMapper;
    private final DeptPort deptPort;
    private final AttendanceApprovalService approvalService;


    // ==================== 管理端查询 ====================

    @Override
    public PageResult<AttendanceVO> pageManage(AttendanceQuery query, PageQuery pageQuery) {
        LambdaQueryWrapper<AttendanceRecord> wrapper = new LambdaQueryWrapper<>();
        applyMonthRange(wrapper, query);

        // 指定员工
        wrapper.eq(query.getEmployeeId() != null, AttendanceRecord::getEmployeeId, query.getEmployeeId());

        // 姓名/工号：先定位员工 ID 集合
        if (StringUtils.isNotBlank(query.getEmployeeName()) || StringUtils.isNotBlank(query.getEmployeeCode())) {
            List<Long> matchedIds = findEmployeeIds(query.getEmployeeName(), query.getEmployeeCode());
            if (matchedIds.isEmpty()) {
                return PageResult.build(List.of(), 0L);
            }
            wrapper.in(AttendanceRecord::getEmployeeId, matchedIds);
        }

        // 部门（含下级）
        if (query.getDeptId() != null) {
            List<Long> deptIds = deptPort.findDeptAndChildIds(query.getDeptId());
            List<Long> employeeIds = employeeMapper.selectList(
                    new LambdaQueryWrapper<Employee>().in(Employee::getDeptId, deptIds))
                .stream().map(Employee::getEmployeeId).toList();
            if (employeeIds.isEmpty()) {
                return PageResult.build(List.of(), 0L);
            }
            wrapper.in(AttendanceRecord::getEmployeeId, employeeIds);
        }

        wrapper.orderByDesc(AttendanceRecord::getAttendMonth)
            .orderByDesc(AttendanceRecord::getId);

        Page<AttendanceRecord> page = attendanceMapper.selectPage(pageQuery.build(), wrapper);
        List<AttendanceVO> vos = page.getRecords().stream().map(this::toVO).toList();
        enrich(vos);
        return PageResult.build(vos, page.getTotal());
    }

    @Override
    public AttendanceVO getById(Long id) {
        AttendanceRecord record = attendanceMapper.selectById(id);
        if (record == null) {
            throw new ServiceException("考勤记录不存在，id={}", id);
        }
        AttendanceVO vo = toVO(record);
        enrich(List.of(vo));
        return vo;
    }

    // ==================== 管理端写入 ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long create(AttendanceSaveDTO dto) {
        assertNotLocked(monthPeriod(dto.getAttendMonth()));
        Employee employee = employeeMapper.selectById(dto.getEmployeeId());
        if (employee == null) {
            throw new ServiceException("员工不存在，employeeId={}", dto.getEmployeeId());
        }
        if (existsConflict(dto.getEmployeeId(), dto.getAttendMonth(), null)) {
            throw new ServiceException("该员工 {} 在 {} 已有考勤记录，不能重复登记",
                employee.getEmployeeName(), dto.getAttendMonth());
        }

        AttendanceRecord record = buildRecord(dto);
        record.setDataSource(DATA_SOURCE_MANUAL);
        try {
            attendanceMapper.insert(record);
        } catch (DuplicateKeyException e) {
            // 并发下 existsConflict 检查与插入之间的竞态由唯一索引兜底
            throw new ServiceException("该员工 {} 在 {} 已有考勤记录，不能重复登记",
                employee.getEmployeeName(), dto.getAttendMonth());
        }
        return record.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(Long id, AttendanceSaveDTO dto) {
        AttendanceRecord record = attendanceMapper.selectById(id);
        if (record == null) {
            throw new ServiceException("考勤记录不存在，id={}", id);
        }
        // 月份允许变更，但原月与目标月任一锁定都拒绝（提交后数据不可动）
        assertNotLocked(monthPeriod(record.getAttendMonth()));
        assertNotLocked(monthPeriod(dto.getAttendMonth()));
        // 员工归属不允许通过编辑修改；月份变更仍需避开同人同月冲突
        if (!Objects.equals(record.getEmployeeId(), dto.getEmployeeId())) {
            throw new ServiceException("不允许修改考勤记录的员工归属");
        }
        if (dto.getVersion() == null) {
            // 禁止空版本更新绕过乐观锁（正常前端编辑必带 version）
            throw new ServiceException("缺少版本号，请刷新页面后重新编辑");
        }
        if (existsConflict(record.getEmployeeId(), dto.getAttendMonth(), id)) {
            throw new ServiceException("该员工在 {} 已存在其他考勤记录", dto.getAttendMonth());
        }

        BeanUtil.copyProperties(buildRecord(dto), record, "id", "dataSource", "createTime", "updateTime");
        record.setRemark(StringUtils.isBlank(dto.getRemark()) ? null : dto.getRemark().trim());
        // 回传乐观锁版本：并发更新时 MP 以 version 作为更新条件
        record.setVersion(dto.getVersion());
        int rows = attendanceMapper.updateById(record);
        if (rows == 0) {
            throw new ServiceException("考勤记录已被他人修改，请刷新后重试");
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteByIds(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        Set<String> months = attendanceMapper.selectByIds(ids).stream()
            .map(AttendanceRecord::getAttendMonth)
            .filter(Objects::nonNull)
            .map(this::monthPeriod)
            .collect(Collectors.toSet());
        Set<String> locked = approvalService.lockedPeriods(months);
        if (!locked.isEmpty()) {
            throw new ServiceException("期间 {} 考勤已提交审批（或已通过），不能删除明细；如需调整请先撤销/驳回审批",
                String.join("、", locked.stream().sorted().toList()));
        }
        attendanceMapper.deleteByIds(ids);
    }

    // ==================== 本人查询（强制身份隔离） ====================

    @Override
    public PageResult<AttendanceVO> pageMy(Long loginUserId, AttendanceQuery query, PageQuery pageQuery) {
        Long myEmployeeId = findEmployeeIdByUserId(loginUserId);
        if (myEmployeeId == null) {
            // 无员工档案的账号（如纯系统管理员）：空页
            return PageResult.build(List.of(), 0L);
        }

        // 安全要点：只用月份区间条件，query 中的 employeeId/employeeName/employeeCode/deptId 一律不读
        LambdaQueryWrapper<AttendanceRecord> wrapper = new LambdaQueryWrapper<AttendanceRecord>()
            .eq(AttendanceRecord::getEmployeeId, myEmployeeId);
        applyMonthRange(wrapper, query);
        wrapper.orderByDesc(AttendanceRecord::getAttendMonth)
            .orderByDesc(AttendanceRecord::getId);

        Page<AttendanceRecord> page = attendanceMapper.selectPage(pageQuery.build(), wrapper);
        List<AttendanceVO> vos = page.getRecords().stream().map(this::toVO).toList();
        enrich(vos);
        return PageResult.build(vos, page.getTotal());
    }

    @Override
    public AttendanceVO getMy(Long id, Long loginUserId) {
        Long myEmployeeId = findEmployeeIdByUserId(loginUserId);
        AttendanceRecord record = attendanceMapper.selectById(id);
        // 不属于本人（或无档案/记录不存在）统一按不存在处理，避免泄露他人记录存在性
        if (myEmployeeId == null || record == null
            || !Objects.equals(record.getEmployeeId(), myEmployeeId)) {
            throw new ServiceException("考勤记录不存在，id={}", id);
        }
        AttendanceVO vo = toVO(record);
        enrich(List.of(vo));
        return vo;
    }

    // ==================== 导入同步（PeopleAttendanceSyncPort） ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void syncAttendanceSummaries(String period, List<AttendanceSummarySyncDTO> summaries) {
        if (summaries == null || summaries.isEmpty()) {
            return;
        }
        List<String> codes = summaries.stream()
            .map(AttendanceSummarySyncDTO::getEmployeeCode)
            .filter(StringUtils::isNotBlank)
            .map(String::trim)
            .distinct()
            .toList();
        if (codes.isEmpty()) {
            log.warn("[考勤同步] 期间 {} 无有效工号，跳过同步", period);
            return;
        }
        Map<String, Long> codeToEmployeeId = employeeMapper.selectList(new LambdaQueryWrapper<Employee>()
                .in(Employee::getEmployeeCode, codes)
                .select(Employee::getEmployeeId, Employee::getEmployeeCode))
            .stream()
            .collect(Collectors.toMap(Employee::getEmployeeCode, Employee::getEmployeeId, (a, b) -> a));

        int synced = 0;
        int skipped = 0;
        for (AttendanceSummarySyncDTO summary : summaries) {
            String code = summary.getEmployeeCode() == null ? null : summary.getEmployeeCode().trim();
            Long employeeId = code == null ? null : codeToEmployeeId.get(code);
            if (employeeId == null || summary.getAttendMonth() == null) {
                skipped++;
                log.warn("[考勤同步] 工号 {} 未匹配员工档案或缺考勤月份，跳过（期间 {}）", code, period);
                continue;
            }
            upsertFromImport(employeeId, summary);
            synced++;
        }
        log.info("[考勤同步] 期间 {} 同步完成：成功 {} 条，跳过 {} 条", period, synced, skipped);
        if (synced > 0) {
            // 数据被导入覆盖，已提交/已通过的审批单失效回待提交，防止按旧审批算薪
            approvalService.invalidateOnDataChange(period);
        }
    }

    /** 导入行 upsert：同员工同月存在则覆盖更新（保留人工备注），否则新增，data_source=DINGTALK */
    private void upsertFromImport(Long employeeId, AttendanceSummarySyncDTO summary) {
        AttendanceRecord existing = attendanceMapper.selectOne(new LambdaQueryWrapper<AttendanceRecord>()
            .eq(AttendanceRecord::getEmployeeId, employeeId)
            .eq(AttendanceRecord::getAttendMonth, summary.getAttendMonth()));
        if (existing == null) {
            AttendanceRecord record = new AttendanceRecord();
            record.setEmployeeId(employeeId);
            record.setAttendMonth(summary.getAttendMonth());
            applyImportMetrics(record, summary);
            record.setDataSource(DATA_SOURCE_DINGTALK);
            try {
                attendanceMapper.insert(record);
            } catch (DuplicateKeyException e) {
                // 并发导入/手工登记撞唯一索引：按最新导入数据覆盖
                AttendanceRecord winner = attendanceMapper.selectOne(new LambdaQueryWrapper<AttendanceRecord>()
                    .eq(AttendanceRecord::getEmployeeId, employeeId)
                    .eq(AttendanceRecord::getAttendMonth, summary.getAttendMonth()));
                if (winner != null) {
                    applyImportMetrics(winner, summary);
                    winner.setDataSource(DATA_SOURCE_DINGTALK);
                    attendanceMapper.updateById(winner);
                }
            }
            return;
        }
        applyImportMetrics(existing, summary);
        existing.setDataSource(DATA_SOURCE_DINGTALK);
        if (attendanceMapper.updateById(existing) == 0) {
            // 乐观锁冲突：他人正在编辑该月记录，导入不覆盖，留待下次导入或人工处理
            log.warn("[考勤同步] 员工 {} {} 月记录正被并发修改，本次未覆盖", employeeId, summary.getAttendMonth());
        }
    }

    /** 导入指标回填：NOT NULL 列空值补 0，出勤/休息天数允许 null（未填写） */
    private void applyImportMetrics(AttendanceRecord record, AttendanceSummarySyncDTO summary) {
        record.setAttendDays(summary.getAttendDays());
        record.setRestDays(summary.getRestDays());
        record.setLateCount(summary.getLateCount() == null ? 0 : summary.getLateCount());
        record.setLateMinutes(summary.getLateMinutes() == null ? 0 : summary.getLateMinutes());
        record.setMissingCardCount(summary.getMissingCardCount() == null ? 0 : summary.getMissingCardCount());
        record.setAbsentDays(summary.getAbsentDays() == null ? BigDecimal.ZERO : summary.getAbsentDays());
        record.setLeaveDays(summary.getLeaveDays() == null ? BigDecimal.ZERO : summary.getLeaveDays());
    }

    // ==================== 内部方法 ====================

    /** 由 DTO 构造记录（不含 dataSource/version/id 等由流程控制的字段） */
    private AttendanceRecord buildRecord(AttendanceSaveDTO dto) {
        AttendanceRecord record = new AttendanceRecord();
        record.setEmployeeId(dto.getEmployeeId());
        record.setAttendMonth(dto.getAttendMonth());
        record.setLeaveDays(dto.getLeaveDays() == null ? BigDecimal.ZERO : dto.getLeaveDays());
        record.setAbsentDays(dto.getAbsentDays() == null ? BigDecimal.ZERO : dto.getAbsentDays());
        record.setLateCount(dto.getLateCount() == null ? 0 : dto.getLateCount());
        record.setLateMinutes(dto.getLateMinutes() == null ? 0 : dto.getLateMinutes());
        record.setMissingCardCount(dto.getMissingCardCount() == null ? 0 : dto.getMissingCardCount());
        record.setAttendDays(dto.getAttendDays());
        record.setRestDays(dto.getRestDays());
        record.setRemark(StringUtils.isBlank(dto.getRemark()) ? null : dto.getRemark().trim());
        return record;
    }

    /**
     * 应用月份区间条件（管理端与本人查询共用）。
     */
    private void applyMonthRange(LambdaQueryWrapper<AttendanceRecord> wrapper, AttendanceQuery query) {
        LocalDate start = parseMonth(query.getMonthStart());
        LocalDate end = parseMonth(query.getMonthEnd());
        wrapper.ge(start != null, AttendanceRecord::getAttendMonth, start)
            .le(end != null, AttendanceRecord::getAttendMonth, end);
    }

    /**
     * 按姓名/工号模糊匹配员工 ID。
     */
    private List<Long> findEmployeeIds(String employeeName, String employeeCode) {
        return employeeMapper.selectList(new LambdaQueryWrapper<Employee>()
                .like(StringUtils.isNotBlank(employeeName), Employee::getEmployeeName, employeeName)
                .like(StringUtils.isNotBlank(employeeCode), Employee::getEmployeeCode, employeeCode)
                .select(Employee::getEmployeeId))
            .stream().map(Employee::getEmployeeId).toList();
    }

    /**
     * 按系统用户 ID 定位员工 ID（员工-账户关联）。
     */
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

    /**
     * 同人同月冲突判断（编辑时排除自身）。
     */
    private boolean existsConflict(Long employeeId, LocalDate attendMonth, Long excludeId) {
        return attendanceMapper.exists(new LambdaQueryWrapper<AttendanceRecord>()
            .eq(AttendanceRecord::getEmployeeId, employeeId)
            .eq(AttendanceRecord::getAttendMonth, attendMonth)
            .ne(excludeId != null, AttendanceRecord::getId, excludeId));
    }

    /** 解析月份参数（yyyy-MM-dd，前端传当月 1 日） */
    private LocalDate parseMonth(String text) {
        if (StringUtils.isBlank(text)) {
            return null;
        }
        try {
            return LocalDate.parse(text.trim());
        } catch (DateTimeParseException e) {
            throw new ServiceException("月份格式不正确，应为 yyyy-MM-dd：{}", text);
        }
    }

    /** LocalDate → 期间（yyyy-MM，审批/锁定口径） */
    private String monthPeriod(LocalDate month) {
        return month == null ? null : month.toString().substring(0, 7);
    }

    /** 期间锁定校验：审批 SUBMITTED/APPROVED 期间禁止手工增删改考勤明细 */
    private void assertNotLocked(String period) {
        if (StringUtils.isNotBlank(period) && approvalService.isPeriodLocked(period)) {
            throw new ServiceException("期间 {} 考勤已提交审批（或已通过），不能修改明细；如需调整请先撤销/驳回审批", period);
        }
    }

    // ==================== 跨域查询（PeopleAttendanceMetricsQueryPort） ====================

    @Override
    public Map<Long, AttendanceMetricsDTO> sumByPeriod(String period) {
        if (StringUtils.isBlank(period)) {
            return Collections.emptyMap();
        }
        LocalDate monthStart;
        try {
            monthStart = LocalDate.parse(period.trim() + "-01");
        } catch (DateTimeParseException e) {
            log.warn("[考勤指标查询] period 格式不合法：{}", period);
            return Collections.emptyMap();
        }
        List<AttendanceRecord> rows = attendanceMapper.selectList(new LambdaQueryWrapper<AttendanceRecord>()
            .eq(AttendanceRecord::getAttendMonth, monthStart));
        if (rows.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<Long, AttendanceMetricsDTO> result = new HashMap<>(rows.size());
        for (AttendanceRecord r : rows) {
            if (r.getEmployeeId() == null) {
                continue;
            }
            AttendanceMetricsDTO dto = new AttendanceMetricsDTO();
            // 考勤事实不含「导入扣款金额」：该字段语义属于月度业绩指标导入的其它扣减
            // （如「7.1-121.31日何方方提成扣2%」），由 MonthlyMetricPort 提供。
            dto.setImportedFee(BigDecimal.ZERO);
            dto.setLateCount(r.getLateCount() == null ? 0 : r.getLateCount());
            dto.setAbsentDays(r.getAbsentDays() == null ? BigDecimal.ZERO : r.getAbsentDays());
            dto.setLeaveDays(r.getLeaveDays() == null ? BigDecimal.ZERO : r.getLeaveDays());
            result.put(r.getEmployeeId(), dto);
        }
        return result;
    }

    private AttendanceVO toVO(AttendanceRecord record) {
        AttendanceVO vo = new AttendanceVO();
        BeanUtil.copyProperties(record, vo);
        return vo;
    }

    /**
     * 批量填充工号/姓名/部门信息。
     */
    private void enrich(List<AttendanceVO> vos) {
        if (vos.isEmpty()) {
            return;
        }
        Set<Long> employeeIds = vos.stream().map(AttendanceVO::getEmployeeId)
            .filter(Objects::nonNull).collect(Collectors.toSet());
        if (employeeIds.isEmpty()) {
            return;
        }
        Map<Long, Employee> employeeMap = employeeMapper.selectByIds(employeeIds).stream()
            .collect(Collectors.toMap(Employee::getEmployeeId, e -> e, (a, b) -> a));

        Set<Long> deptIds = employeeMap.values().stream().map(Employee::getDeptId)
            .filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, String> deptNames = deptIds.isEmpty() ? Map.of() : deptPort.findDeptFullNames(deptIds);

        // 行级锁定标记：命中锁定期间（SUBMITTED/APPROVED）的行前端隐藏修改/删除按钮
        Set<String> months = vos.stream().map(AttendanceVO::getAttendMonth)
            .filter(Objects::nonNull).map(this::monthPeriod).collect(Collectors.toSet());
        Set<String> lockedPeriods = approvalService.lockedPeriods(months);

        for (AttendanceVO vo : vos) {
            Employee employee = employeeMap.get(vo.getEmployeeId());
            if (employee != null) {
                vo.setEmployeeCode(employee.getEmployeeCode());
                vo.setEmployeeName(employee.getEmployeeName());
                vo.setDeptId(employee.getDeptId());
                if (employee.getDeptId() != null) {
                    vo.setDeptName(deptNames.get(employee.getDeptId()));
                }
            }
            vo.setLocked(lockedPeriods.contains(monthPeriod(vo.getAttendMonth())));
        }
    }
}
