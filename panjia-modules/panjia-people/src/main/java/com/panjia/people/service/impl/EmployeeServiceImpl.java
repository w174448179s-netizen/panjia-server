package com.panjia.people.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.panjia.people.domain.ChangeLog;
import com.panjia.people.domain.Employee;
import com.panjia.people.domain.EmployeeStatus;
import com.panjia.people.domain.FactType;
import com.panjia.people.domain.SalaryFact;
import com.panjia.people.domain.SalaryRecord;
import com.panjia.people.dto.AccountUserInfo;
import com.panjia.people.dto.ChangeLogVO;
import com.panjia.people.dto.DeptCategoryInfo;
import com.panjia.people.dto.EmployeeCreateDTO;
import com.panjia.people.dto.EmployeeQuery;
import com.panjia.people.dto.EmployeeUpdateDTO;
import com.panjia.people.dto.EmployeeVO;
import com.panjia.people.dto.VirtualEmployeeCandidateVO;
import com.panjia.people.dto.VirtualEmployeeInitDTO;
import com.panjia.people.dto.VirtualEmployeeInitResultVO;
import com.panjia.people.mapper.ChangeLogMapper;
import com.panjia.people.mapper.EmployeeMapper;
import com.panjia.people.mapper.SalaryFactMapper;
import com.panjia.people.mapper.SalaryRecordMapper;
import com.panjia.people.port.AccountPort;
import com.panjia.people.port.DeptPort;
import com.panjia.people.service.EmployeeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.domain.PageResult;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 员工服务实现（V5.2）。
 * <p>
 * 只依赖 Port 接口操作 sys_*；变更统一走 {@code changeFact}（闭开区间），
 * 部门/岗位变更同事务同步账户，离职禁用账户。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmployeeServiceImpl implements EmployeeService {

    /** 变更字段：入职初始化 */
    private static final String CHANGE_FIELD_ALL = "ALL";
    /** 变更字段：归属部门 */
    private static final String CHANGE_FIELD_DEPT = "DEPT";
    /** 变更字段：岗位与角色 */
    private static final String CHANGE_FIELD_POSTS = "POSTS";
    /** 变更字段：生效日调整（值不变，仅平移事实生效日） */
    private static final String CHANGE_FIELD_EFFECTIVE = "EFFECTIVE";
    /** 布尔事实真值 */
    private static final String BOOL_TRUE = "true";
    /** 布尔事实假值 */
    private static final String BOOL_FALSE = "false";
    /** 无伯乐时 MENTOR 事实存储值 */
    private static final String NO_MENTOR = "";
    /**
     * 门店虚拟角色人识别方式（2026-09-28 改）：
     * 原方案虚拟人工号以 99999 开头识别；现改为「工号 = 店组编码」，
     * 即虚拟人 employee_code 直接等于 sys_dept.dept_category（如 CD_15_1696157）。
     * 初始化脚本见 script/sql/init_virtual_roles.sql。
     */
    private static final Long SYSTEM_OPERATOR_ID = 0L;

    private final EmployeeMapper employeeMapper;
    private final SalaryFactMapper salaryFactMapper;
    private final SalaryRecordMapper salaryRecordMapper;
    private final ChangeLogMapper changeLogMapper;
    private final AccountPort accountPort;
    private final DeptPort deptPort;

    // ==================== 查询 ====================

    @Override
    public PageResult<EmployeeVO> pageList(EmployeeQuery query, PageQuery pageQuery) {
        List<EmployeeStatus> statuses = EmployeeStatus.fromCodes(query.getStatus());
        LambdaQueryWrapper<Employee> wrapper = new LambdaQueryWrapper<Employee>()
            .like(StringUtils.isNotBlank(query.getEmployeeCode()), Employee::getEmployeeCode, query.getEmployeeCode())
            .like(StringUtils.isNotBlank(query.getEmployeeName()), Employee::getEmployeeName, query.getEmployeeName())
            .in(!statuses.isEmpty(), Employee::getStatus, statuses)
            .orderByDesc(Employee::getCreateTime)
            .orderByDesc(Employee::getEmployeeId);

        if (query.getDeptId() != null) {
            List<Long> deptIds = deptPort.findDeptAndChildIds(query.getDeptId());
            wrapper.in(Employee::getDeptId, deptIds);
        }
        if (StringUtils.isNotBlank(query.getPostName())) {
            List<Long> userIds = accountPort.findUserIdsByPostName(query.getPostName());
            if (userIds.isEmpty()) {
                return PageResult.build(List.of(), 0L);
            }
            wrapper.in(Employee::getUserId, userIds);
        }

        Page<Employee> page = employeeMapper.selectPage(pageQuery.build(), wrapper);
        List<EmployeeVO> vos = page.getRecords().stream().map(this::toVO).toList();
        enrich(vos);
        return PageResult.build(vos, page.getTotal());
    }

    @Override
    public EmployeeVO getDetail(Long employeeId) {
        Employee emp = employeeMapper.selectById(employeeId);
        if (emp == null) {
            throw new ServiceException("员工不存在，employeeId={}", employeeId);
        }
        EmployeeVO vo = toVO(emp);
        enrich(List.of(vo));
        return vo;
    }

    @Override
    public List<ChangeLogVO> getHistory(Long employeeId) {
        List<ChangeLog> logs = changeLogMapper.selectByEmployee(employeeId);
        Set<Long> operatorIds = logs.stream()
            .map(ChangeLog::getOperatorId)
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());
        Map<Long, String> operatorNames = operatorIds.isEmpty()
            ? Map.of()
            : accountPort.findNicknamesByIds(operatorIds);

        return logs.stream().map(log -> {
            ChangeLogVO vo = new ChangeLogVO();
            BeanUtil.copyProperties(log, vo);
            vo.setChangeFieldName(displayFieldName(log.getChangeField()));
            vo.setOperatorName(log.getOperatorId() != null ? operatorNames.get(log.getOperatorId()) : null);
            if (FactType.MENTOR.getCode().equals(log.getChangeField())) {
                vo.setBeforeValue(displayMentor(log.getBeforeValue()));
                vo.setAfterValue(displayMentor(log.getAfterValue()));
            }
            return vo;
        }).toList();
    }

    // ==================== 新增 ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long createEmployee(EmployeeCreateDTO dto, Long operatorId) {
        Long exists = employeeMapper.selectCount(new LambdaQueryWrapper<Employee>()
            .eq(Employee::getEmployeeCode, dto.getEmployeeCode()));
        if (exists != null && exists > 0) {
            throw new ServiceException("工号 {} 已存在", dto.getEmployeeCode());
        }
        // 导入侧预解析后可直传 mentorId 绕过库内查询（伯乐在本批次但尚未入库的场景）；
        // 员工管理界面路径不传 mentorId，回退到 mentorCode 走库内解析
        Long mentorId = dto.getMentorId() != null
            ? dto.getMentorId()
            : resolveMentorIdByCode(dto.getMentorCode());

        Employee emp = new Employee();
        emp.setEmployeeCode(dto.getEmployeeCode().trim());
        emp.setEmployeeName(dto.getEmployeeName().trim());
        emp.setDeptId(dto.getDeptId());
        emp.setPhone(dto.getPhone());
        emp.setIdCard(dto.getIdCard());
        emp.setReportDate(dto.getReportDate());
        emp.setHireDate(dto.getHireDate());
        EmployeeStatus status = EmployeeStatus.fromCode(dto.getStatus());
        emp.setStatus(status != null ? status : EmployeeStatus.ACTIVE);
        emp.setMentorEmployeeId(mentorId);
        emp.setRemark(dto.getRemark());
        employeeMapper.insert(emp);

        // 建系统账户 + 岗位/角色（同事务）
        Long userId = accountPort.createUser(
            emp.getEmployeeCode(), emp.getEmployeeName(), emp.getDeptId(), dto.getPostNames());
        emp.setUserId(userId);
        employeeMapper.updateById(emp);

        Long operator = operatorId != null ? operatorId : SYSTEM_OPERATOR_ID;
        // 事实生效粒度 = 月（算薪按月）：生效日统一收敛到当月 1 日，月末切片取数与月粒度等价
        LocalDate effect = monthStart(emp.getHireDate());
        // 8 类算薪事实初始记录（change_field=ALL，生效日=入职日）
        initFact(emp.getEmployeeId(), FactType.LEVEL, dto.getLevelCode(), effect);
        initFact(emp.getEmployeeId(), FactType.STATUS, emp.getStatus().getCode(), effect);
        initFact(emp.getEmployeeId(), FactType.SOCIAL, boolVal(dto.getSocialInsured()), effect);
        initFact(emp.getEmployeeId(), FactType.SOCIAL_FEE, decimalVal(dto.getSocialFee()), effect);
        initFact(emp.getEmployeeId(), FactType.HOUSING, boolVal(dto.getHousingInsured()), effect);
        initFact(emp.getEmployeeId(), FactType.COMMERCIAL, boolVal(dto.getCommercialInsured()), effect);
        initFact(emp.getEmployeeId(), FactType.COMMERCIAL_FEE, decimalVal(dto.getCommercialFee()), effect);
        initFact(emp.getEmployeeId(), FactType.DORMITORY, boolVal(dto.getDormitory()), effect);
        initFact(emp.getEmployeeId(), FactType.HOUSING_FUND, decimalVal(dto.getHousingFund()), effect);
        initFact(emp.getEmployeeId(), FactType.DORMITORY_FEE, decimalVal(dto.getDormitoryFee()), effect);
        initFact(emp.getEmployeeId(), FactType.PARTTIME, boolVal(dto.getParttime()), effect);
        initFact(emp.getEmployeeId(), FactType.EXCLUDE_TEAM, boolVal(dto.getExcludeTeam()), effect);
        initFact(emp.getEmployeeId(), FactType.MENTOR,
            mentorId != null ? String.valueOf(mentorId) : NO_MENTOR, effect);

        writeLog(emp.getEmployeeId(), CHANGE_FIELD_ALL, null, "入职初始化", effect, operator);
        refreshRecord(emp);

        return emp.getEmployeeId();
    }

    // ==================== 修改（统一 diff） ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateEmployee(Long employeeId, EmployeeUpdateDTO dto, Long operatorId) {
        Employee emp = employeeMapper.selectById(employeeId);
        if (emp == null) {
            throw new ServiceException("员工不存在，employeeId={}", employeeId);
        }
        Long operator = operatorId != null ? operatorId : SYSTEM_OPERATOR_ID;
        LocalDate effect = monthStart(dto.getEffectiveDate() != null ? dto.getEffectiveDate() : LocalDate.now());

        // ① 基本信息直改
        if (StringUtils.isNotBlank(dto.getEmployeeName())) {
            emp.setEmployeeName(dto.getEmployeeName().trim());
        }
        if (dto.getPhone() != null) {
            emp.setPhone(dto.getPhone());
        }
        if (dto.getIdCard() != null) {
            emp.setIdCard(dto.getIdCard());
        }
        if (dto.getReportDate() != null) {
            emp.setReportDate(dto.getReportDate());
        }
        if (dto.getHireDate() != null) {
            emp.setHireDate(dto.getHireDate());
        }
        if (dto.getRemark() != null) {
            emp.setRemark(dto.getRemark());
        }
        if (dto.getLeaveDate() != null) {
            emp.setLeaveDate(dto.getLeaveDate());
        }

        // ② 伯乐（mentorCode 非 null 即参与比对；空白=解除伯乐关系）
        if (dto.getMentorCode() != null) {
            Long newMentorId = resolveMentorIdByCode(dto.getMentorCode());
            if (!Objects.equals(newMentorId, emp.getMentorEmployeeId())) {
                changeFact(employeeId, FactType.MENTOR,
                    newMentorId != null ? String.valueOf(newMentorId) : NO_MENTOR, effect, operator);
                emp.setMentorEmployeeId(newMentorId);
            }
        }

        // ③ 状态（离职 → 禁用账户）
        if (StringUtils.isNotBlank(dto.getStatus())) {
            EmployeeStatus newStatus = EmployeeStatus.fromCode(dto.getStatus());
            if (newStatus == null) {
                throw new ServiceException("非法员工状态：{}", dto.getStatus());
            }
            if (newStatus != emp.getStatus()) {
                changeFact(employeeId, FactType.STATUS, newStatus.getCode(), effect, operator);
                emp.setStatus(newStatus);
                if (newStatus == EmployeeStatus.LEFT) {
                    emp.setLeaveDate(dto.getLeaveDate() != null ? dto.getLeaveDate() : effect);
                    if (emp.getUserId() != null) {
                        accountPort.disableUser(emp.getUserId());
                    }
                }
            }
        }

        // ④ 职级（只走 fact，与登录/权限无关）
        if (StringUtils.isNotBlank(dto.getLevelCode())) {
            String current = salaryFactMapper.selectValueAt(employeeId, FactType.LEVEL, effect);
            if (!dto.getLevelCode().trim().equals(current)) {
                changeFact(employeeId, FactType.LEVEL, dto.getLevelCode().trim(), effect, operator);
            }
        }

        // ⑤ 算薪开关/金额（只走 fact）
        changeBoolFact(employeeId, FactType.SOCIAL, dto.getSocialInsured(), effect, operator);
        changeDecimalFact(employeeId, FactType.SOCIAL_FEE, dto.getSocialFee(), effect, operator);
        changeBoolFact(employeeId, FactType.HOUSING, dto.getHousingInsured(), effect, operator);
        changeBoolFact(employeeId, FactType.COMMERCIAL, dto.getCommercialInsured(), effect, operator);
        changeDecimalFact(employeeId, FactType.COMMERCIAL_FEE, dto.getCommercialFee(), effect, operator);
        changeBoolFact(employeeId, FactType.DORMITORY, dto.getDormitory(), effect, operator);
        changeDecimalFact(employeeId, FactType.HOUSING_FUND, dto.getHousingFund(), effect, operator);
        changeDecimalFact(employeeId, FactType.DORMITORY_FEE, dto.getDormitoryFee(), effect, operator);
        changeBoolFact(employeeId, FactType.PARTTIME, dto.getParttime(), effect, operator);
        changeBoolFact(employeeId, FactType.EXCLUDE_TEAM, dto.getExcludeTeam(), effect, operator);

        // ⑥ 归属部门变更 → 同步 sys_user.dept_id
        if (dto.getDeptId() != null && !dto.getDeptId().equals(emp.getDeptId())) {
            String beforeName = deptPort.findDeptFullName(emp.getDeptId());
            if (emp.getUserId() != null) {
                accountPort.updateDept(emp.getUserId(), dto.getDeptId());
            }
            emp.setDeptId(dto.getDeptId());
            String afterName = deptPort.findDeptFullName(dto.getDeptId());
            writeLog(employeeId, CHANGE_FIELD_DEPT, beforeName, afterName, effect, operator);
        }

        // ⑦ 岗位集合变更 → 重建 sys_user_post + 推导重建 sys_user_role（先清后绑）
        if (dto.getPostNames() != null && emp.getUserId() != null) {
            List<String> target = dto.getPostNames().stream()
                .filter(StringUtils::isNotBlank)
                .map(String::trim)
                .sorted()
                .distinct()
                .toList();
            AccountUserInfo info = accountPort.loadUser(emp.getUserId());
            List<String> current = info != null && info.getPostNames() != null
                ? info.getPostNames().stream().sorted().distinct().toList()
                : List.of();
            if (!current.equals(target)) {
                accountPort.rebuildPostsAndRoles(emp.getUserId(), target);
                writeLog(employeeId, CHANGE_FIELD_POSTS,
                    String.join("/", current), String.join("/", target), effect, operator);
            }
        }

        employeeMapper.updateById(emp);
        refreshRecord(emp);
    }

    /**
     * 调整算薪事实生效日期（值不变，仅平移生效日）。
     * <p>
     * 逐项处理所选变更项：平移当前开放事实的起点，并把前一段闭合点对齐到新日期
     * （保持"前段终点=开放段起点"的链条不变式），写"生效日调整"日志留痕。
     * 已是指定日期的项自动跳过；全部跳过视为无操作报错，避免用户以为调成功了。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int adjustFactDates(Long employeeId, List<String> factTypes, LocalDate newDate, Long operatorId) {
        Employee emp = employeeMapper.selectById(employeeId);
        if (emp == null) {
            throw new ServiceException("员工不存在，employeeId={}", employeeId);
        }
        if (newDate == null) {
            throw new ServiceException("新生效日期不能为空");
        }
        // 生效粒度 = 月：统一收敛到当月 1 日
        final LocalDate target = monthStart(newDate);
        boolean all = factTypes == null || factTypes.isEmpty()
            || factTypes.stream().anyMatch(t -> "ALL".equalsIgnoreCase(t));
        List<FactType> types = all
            ? List.of(FactType.values())
            : factTypes.stream().map(FactType::fromCode).filter(Objects::nonNull).distinct().toList();
        if (types.isEmpty()) {
            throw new ServiceException("未指定有效的变更项");
        }
        Long operator = operatorId != null ? operatorId : SYSTEM_OPERATOR_ID;
        int changed = 0;
        for (FactType type : types) {
            SalaryFact open = salaryFactMapper.selectOpenFact(employeeId, type);
            if (open == null || open.getEffectiveDate().equals(target)) {
                continue;
            }
            LocalDate oldDate = open.getEffectiveDate();
            salaryFactMapper.shiftPrevExpire(employeeId, type, oldDate, target, open.getFactId());
            salaryFactMapper.updateEffectiveDate(open.getFactId(), target);
            writeLog(employeeId, CHANGE_FIELD_EFFECTIVE,
                type.getDesc() + "：原生效月 " + oldDate.toString().substring(0, 7),
                "调整为 " + target.toString().substring(0, 7), target, operator);
            changed++;
        }
        if (changed == 0) {
            throw new ServiceException("所选变更项的生效月均已是 {}，无需调整", target.toString().substring(0, 7));
        }
        return changed;
    }

    /**
     * 算薪事实生效粒度 = 月：生效日统一收敛到当月 1 日（月末切片取数与月粒度天然等价）。
     */
    private static LocalDate monthStart(LocalDate date) {
        return (date != null ? date : LocalDate.now()).withDayOfMonth(1);
    }

    // ==================== 算薪快照（PeopleQueryPort） ====================

    @Override
    public Map<String, String> getSnapshotAt(Long employeeId, LocalDate pointInMonth) {
        LocalDate point = monthEnd(pointInMonth);
        Map<String, String> snap = new LinkedHashMap<>();
        for (FactType type : FactType.values()) {
            snap.put(type.getCode(), salaryFactMapper.selectValueAt(employeeId, type, point));
        }
        return snap;
    }

    @Override
    public Map<Long, Map<String, String>> getSnapshotsAt(Collection<Long> employeeIds, LocalDate pointInMonth) {
        LocalDate point = monthEnd(pointInMonth);
        List<Long> ids = List.copyOf(employeeIds);
        Map<Long, Map<String, String>> result = new LinkedHashMap<>();
        for (Long id : ids) {
            Map<String, String> snap = new LinkedHashMap<>();
            for (FactType type : FactType.values()) {
                snap.put(type.getCode(), null);
            }
            result.put(id, snap);
        }
        for (FactType type : FactType.values()) {
            for (SalaryFact fact : salaryFactMapper.selectValuesAt(ids, type, point)) {
                result.get(fact.getEmployeeId()).put(type.getCode(), fact.getValue());
            }
        }
        return result;
    }

    @Override
    public Map<Long, LocalDate> findLevelEffectiveDatesAt(Collection<Long> employeeIds, LocalDate pointInMonth) {
        if (employeeIds == null || employeeIds.isEmpty()) {
            return Map.of();
        }
        LocalDate point = monthEnd(pointInMonth);
        Map<Long, LocalDate> result = new LinkedHashMap<>();
        for (SalaryFact fact : salaryFactMapper.selectValuesAt(List.copyOf(employeeIds), FactType.LEVEL, point)) {
            result.put(fact.getEmployeeId(), fact.getEffectiveDate());
        }
        return result;
    }

    @Override
    public com.panjia.contracts.snapshot.EmployeeSnapshot getEmployeeSnapshot(
            Long employeeId, LocalDate pointInMonth) {
        if (employeeId == null) {
            return null;
        }
        Employee emp = employeeMapper.selectById(employeeId);
        if (emp == null) {
            return null;
        }
        com.panjia.contracts.snapshot.EmployeeSnapshot snap =
            assembleSnapshot(emp, getSnapshotAt(employeeId, pointInMonth), monthEnd(pointInMonth));
        // 填充 position（sys_user_post → sys_post.post_name）供角色判定
        if (emp.getUserId() != null) {
            Map<Long, List<String>> postNameMap = accountPort.findPostNamesByUserIds(List.of(emp.getUserId()));
            List<String> names = postNameMap.get(emp.getUserId());
            if (names != null && !names.isEmpty()) {
                snap.setPosition(names.get(0));
            }
        }
        return snap;
    }

    @Override
    public Map<Long, com.panjia.contracts.snapshot.EmployeeSnapshot> getEmployeeSnapshots(
            Collection<Long> employeeIds, LocalDate pointInMonth) {
        if (employeeIds == null || employeeIds.isEmpty()) {
            return Map.of();
        }
        LocalDate point = monthEnd(pointInMonth);
        Map<Long, Map<String, String>> facts = getSnapshotsAt(employeeIds, pointInMonth);
        List<Employee> employees = employeeMapper.selectList(
            new LambdaQueryWrapper<Employee>().in(Employee::getEmployeeId, employeeIds));
        // 批量查岗位名（sys_user_post → sys_post.post_name），填充 position 供角色判定
        List<Long> userIds = employees.stream()
            .map(Employee::getUserId)
            .filter(Objects::nonNull)
            .toList();
        Map<Long, List<String>> postNameMap = userIds.isEmpty()
            ? Map.of() : accountPort.findPostNamesByUserIds(userIds);
        Map<Long, com.panjia.contracts.snapshot.EmployeeSnapshot> result = new LinkedHashMap<>();
        for (Employee emp : employees) {
            com.panjia.contracts.snapshot.EmployeeSnapshot snap =
                assembleSnapshot(emp, facts.get(emp.getEmployeeId()), point);
            List<String> names = emp.getUserId() == null ? null : postNameMap.get(emp.getUserId());
            if (names != null && !names.isEmpty()) {
                snap.setPosition(names.get(0));
            }
            result.put(emp.getEmployeeId(), snap);
        }
        return result;
    }

    @Override
    public Collection<Long> findEmployeeIdsByLevels(Collection<String> levelCodes, LocalDate pointInMonth) {
        if (levelCodes == null || levelCodes.isEmpty()) {
            return List.of();
        }
        return salaryFactMapper.selectEmployeeIdsByFactValue(
            List.copyOf(levelCodes), FactType.LEVEL, monthEnd(pointInMonth));
    }

    @Override
    public Collection<Long> findActiveEmployeeIdsWithLevel(LocalDate pointInMonth) {
        return salaryFactMapper.selectActiveEmployeeIdsByFactType(FactType.LEVEL, monthEnd(pointInMonth));
    }

    @Override
    public Map<Long, Long> findStoreAnchors(Collection<Long> deptIds) {
        return deptPort.findStoreAnchors(deptIds);
    }

    @Override
    public Map<Long, String> findStoreDepts() {
        return deptPort.findStoreDepts();
    }

    @Override
    public Map<Long, List<Long>> findDeptAndChildren(Collection<Long> deptIds) {
        if (deptIds == null || deptIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, List<Long>> result = new LinkedHashMap<>();
        for (Long deptId : deptIds) {
            if (deptId == null) continue;
            List<Long> ids = deptPort.findDeptAndChildIds(deptId);
            result.put(deptId, ids != null ? ids : new ArrayList<>());
        }
        return result;
    }

    @Override
    public Map<Long, List<Long>> findDirectChildren(Collection<Long> deptIds) {
        if (deptIds == null || deptIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, List<Long>> result = new LinkedHashMap<>();
        for (Long deptId : deptIds) {
            if (deptId == null) continue;
            result.put(deptId, deptPort.findDirectChildIds(deptId));
        }
        return result;
    }

    @Override
    public Map<Long, String> findDeptNames(Collection<Long> deptIds) {
        if (deptIds == null || deptIds.isEmpty()) {
            return Map.of();
        }
        return deptPort.findDeptFullNames(deptIds);
    }

    /**
     * 组装 §10.6 强类型快照：身份取员工主数据当前行，事实取月末闭开区间切片。
     * <p>
     * position 由调用方通过 AccountPort.findPostNamesByUserIds 查询后填充，
     * 本方法仅组装事实层字段。
     */
    private com.panjia.contracts.snapshot.EmployeeSnapshot assembleSnapshot(
            Employee emp, Map<String, String> factValues, LocalDate point) {
        com.panjia.contracts.snapshot.EmployeeSnapshot snap =
            new com.panjia.contracts.snapshot.EmployeeSnapshot();
        // 身份
        snap.setEmployeeId(emp.getEmployeeId());
        snap.setEmployeeCode(emp.getEmployeeCode());
        snap.setEmployeeName(emp.getEmployeeName());
        snap.setUserId(emp.getUserId());
        snap.setDeptId(emp.getDeptId());
        snap.setHireDate(emp.getHireDate());
        if (factValues != null) {
            // 职级
            snap.setLevelCode(factValues.get(FactType.LEVEL.getCode()));
            // 开关类事实
            snap.setSocialInsured(parseBoolFact(factValues.get(FactType.SOCIAL.getCode())));
            snap.setHousingInsured(parseBoolFact(factValues.get(FactType.HOUSING.getCode())));
            snap.setCommercialInsured(parseBoolFact(factValues.get(FactType.COMMERCIAL.getCode())));
            snap.setDormitory(parseBoolFact(factValues.get(FactType.DORMITORY.getCode())));
            snap.setIsPartTime(parseBoolFact(factValues.get(FactType.PARTTIME.getCode())));
            // 金额类事实
            snap.setSocialFee(parseDecimalFact(factValues.get(FactType.SOCIAL_FEE.getCode())));
            snap.setCommercialFee(parseDecimalFact(factValues.get(FactType.COMMERCIAL_FEE.getCode())));
            snap.setHousingFund(parseDecimalFact(factValues.get(FactType.HOUSING_FUND.getCode())));
            snap.setDormitoryFee(parseDecimalFact(factValues.get(FactType.DORMITORY_FEE.getCode())));
            // 伯乐
            snap.setMentorId(parseMentorFact(factValues.get(FactType.MENTOR.getCode())));
            // 团队业绩排除开关
            snap.setExcludeTeam(parseBoolFact(factValues.get(FactType.EXCLUDE_TEAM.getCode())));
        }
        snap.setSnapshotDate(point);
        snap.setSnapshottedAt(java.time.LocalDateTime.now());
        return snap;
    }

    /** 布尔事实值解析："true"→TRUE，"false"→FALSE，null/空→null（不臆造默认值） */
    private Boolean parseBoolFact(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return Boolean.parseBoolean(value.trim());
    }

    /** 金额事实值解析：空/非数字 → null */
    private java.math.BigDecimal parseDecimalFact(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return new java.math.BigDecimal(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 金额事实值解析（refreshRecord 用）：空/非数字 → null */
    private java.math.BigDecimal parseDecimal(String value) {
        return parseDecimalFact(value);
    }

    /** BigDecimal → 事实存储字符串；null → null */
    private String decimalVal(java.math.BigDecimal val) {
        return val != null ? val.toPlainString() : null;
    }

    /** MENTOR 事实值解析：空串/空白/非数字 → null（无伯乐） */
    private Long parseMentorFact(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @Override
    public Map<String, Long> findEmployeeIdsByCodes(Collection<String> codes) {
        if (codes == null || codes.isEmpty()) {
            return Map.of();
        }
        List<Employee> list = employeeMapper.selectList(
            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Employee>()
                .in(Employee::getEmployeeCode, codes));
        Map<String, Long> map = new java.util.LinkedHashMap<>();
        for (Employee e : list) {
            map.put(e.getEmployeeCode(), e.getEmployeeId());
        }
        return map;
    }

    @Override
    public Map<String, com.panjia.contracts.dto.EmployeeMainDataDTO> findVirtualEmployeesByStoreGroups(
            Collection<String> storeGroups) {
        if (storeGroups == null || storeGroups.isEmpty()) {
            return Map.of();
        }
        // 1. 店组编码（= 部门类别编码 dept_category）→ 门店部门
        Map<String, Long> deptByCategory = deptPort.findActiveDeptIdsByCategories(storeGroups);
        if (deptByCategory.isEmpty()) {
            return Map.of();
        }
        // 2. 部门 → 虚拟人（工号 = 店组编码，即 employee_code IN storeGroups；同部门多条取工号最小的第一个）
        List<Employee> virtuals = employeeMapper.selectList(new LambdaQueryWrapper<Employee>()
            .in(Employee::getDeptId, deptByCategory.values())
            .in(Employee::getEmployeeCode, storeGroups)
            .orderByAsc(Employee::getEmployeeCode));
        Map<Long, Employee> byDept = new LinkedHashMap<>();
        for (Employee e : virtuals) {
            byDept.putIfAbsent(e.getDeptId(), e);
        }
        // 3. 组装 店组编码 → 虚拟人主数据；部门下无虚拟人 → 不含该键（调用方按匹配失败处理）
        Map<String, com.panjia.contracts.dto.EmployeeMainDataDTO> result = new LinkedHashMap<>();
        deptByCategory.forEach((category, deptId) -> {
            Employee v = byDept.get(deptId);
            if (v == null) {
                return;
            }
            com.panjia.contracts.dto.EmployeeMainDataDTO dto = new com.panjia.contracts.dto.EmployeeMainDataDTO();
            dto.setEmployeeId(v.getEmployeeId());
            dto.setEmployeeCode(v.getEmployeeCode());
            dto.setEmployeeName(v.getEmployeeName());
            dto.setDeptId(v.getDeptId());
            result.put(category, dto);
        });
        return result;
    }

    @Override
    public Map<String, com.panjia.contracts.dto.EmployeeMainDataDTO> findEmployeeMainDataByCodes(Collection<String> codes) {
        if (codes == null || codes.isEmpty()) {
            return Map.of();
        }
        List<Employee> list = employeeMapper.selectList(new LambdaQueryWrapper<Employee>()
            .in(Employee::getEmployeeCode, codes));
        Map<String, com.panjia.contracts.dto.EmployeeMainDataDTO> result = new LinkedHashMap<>();
        for (Employee e : list) {
            com.panjia.contracts.dto.EmployeeMainDataDTO dto = new com.panjia.contracts.dto.EmployeeMainDataDTO();
            dto.setEmployeeId(e.getEmployeeId());
            dto.setEmployeeCode(e.getEmployeeCode());
            dto.setEmployeeName(e.getEmployeeName());
            dto.setDeptId(e.getDeptId());
            result.putIfAbsent(e.getEmployeeCode(), dto);
        }
        return result;
    }

    @Override
    public List<VirtualEmployeeCandidateVO> listVirtualCandidates() {
        List<DeptCategoryInfo> depts = deptPort.listActiveDeptCategories();
        if (depts.isEmpty()) {
            return List.of();
        }
        // 虚拟人判定口径与 findVirtualEmployeesByStoreGroups 一致：部门下 工号 = 店组编码
        List<String> categories = depts.stream()
            .map(DeptCategoryInfo::getDeptCategory)
            .filter(StringUtils::isNotBlank)
            .distinct()
            .toList();
        Map<String, Long> virtualIdByKey = new LinkedHashMap<>();
        if (!categories.isEmpty()) {
            List<Employee> virtuals = employeeMapper.selectList(new LambdaQueryWrapper<Employee>()
                .in(Employee::getDeptId, depts.stream().map(DeptCategoryInfo::getDeptId).toList())
                .in(Employee::getEmployeeCode, categories));
            for (Employee e : virtuals) {
                virtualIdByKey.putIfAbsent(e.getDeptId() + "|" + e.getEmployeeCode(), e.getEmployeeId());
            }
        }
        Map<Long, String> fullNames = deptPort.findDeptFullNames(
            depts.stream().map(DeptCategoryInfo::getDeptId).toList());
        List<VirtualEmployeeCandidateVO> result = new ArrayList<>(depts.size());
        for (DeptCategoryInfo d : depts) {
            VirtualEmployeeCandidateVO vo = new VirtualEmployeeCandidateVO();
            vo.setDeptId(d.getDeptId());
            vo.setDeptFullName(fullNames.getOrDefault(d.getDeptId(), d.getDeptName()));
            vo.setDeptCategory(d.getDeptCategory());
            Long vid = StringUtils.isBlank(d.getDeptCategory()) ? null
                : virtualIdByKey.get(d.getDeptId() + "|" + d.getDeptCategory());
            vo.setHasVirtual(vid != null);
            vo.setVirtualEmployeeId(vid);
            result.add(vo);
        }
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public VirtualEmployeeInitResultVO initVirtualEmployees(List<VirtualEmployeeInitDTO> items, Long operatorId) {
        VirtualEmployeeInitResultVO result = new VirtualEmployeeInitResultVO();
        if (items == null || items.isEmpty()) {
            return result;
        }
        // 批次内编码重复 → 直接拒绝
        Set<String> batchCodes = new HashSet<>();
        for (VirtualEmployeeInitDTO item : items) {
            String code = item.getDeptCategory() == null ? "" : item.getDeptCategory().trim();
            if (code.isEmpty()) {
                throw new ServiceException("店组编码不能为空（部门 ID " + item.getDeptId() + "）");
            }
            if (!batchCodes.add(code)) {
                throw new ServiceException("店组编码在本次提交中重复: " + code);
            }
        }
        // 现有编码归属 + 部门名（一次查询两用）
        List<DeptCategoryInfo> depts = deptPort.listActiveDeptCategories();
        Map<String, Long> categoryOwner = new LinkedHashMap<>();
        Map<Long, String> nameByDept = new LinkedHashMap<>();
        for (DeptCategoryInfo d : depts) {
            nameByDept.put(d.getDeptId(), d.getDeptName());
            if (StringUtils.isNotBlank(d.getDeptCategory())) {
                categoryOwner.putIfAbsent(d.getDeptCategory(), d.getDeptId());
            }
        }
        for (VirtualEmployeeInitDTO item : items) {
            String code = item.getDeptCategory().trim();
            // 编码被其它部门占用 → 冲突剔除（防两店组同编码导致虚拟人挂靠错乱）
            Long owner = categoryOwner.get(code);
            if (owner != null && !owner.equals(item.getDeptId())) {
                log.warn("[虚拟人初始化] 编码被其它部门占用：code={}, ownerDeptId={}, requestDeptId={}",
                    code, owner, item.getDeptId());
                result.getConflictedCodes().add(code);
                continue;
            }
            // 1. 写入店组编码（sys_dept.dept_category）
            deptPort.updateDeptCategory(item.getDeptId(), code);
            // 2. 幂等建虚拟人：部门下工号=编码已存在则跳过
            Long exists = employeeMapper.selectCount(new LambdaQueryWrapper<Employee>()
                .eq(Employee::getDeptId, item.getDeptId())
                .eq(Employee::getEmployeeCode, code));
            if (exists != null && exists > 0) {
                result.getSkippedCodes().add(code);
                continue;
            }
            Employee v = new Employee();
            v.setEmployeeCode(code);
            v.setEmployeeName(nameByDept.getOrDefault(item.getDeptId(), code));
            v.setDeptId(item.getDeptId());
            v.setHireDate(LocalDate.now());
            v.setStatus(EmployeeStatus.ACTIVE);
            employeeMapper.insert(v);
            result.getCreatedCodes().add(code);
        }
        log.info("[虚拟人初始化] operator={}, created={}, skipped={}, conflicted={}",
            operatorId, result.getCreatedCodes(), result.getSkippedCodes(), result.getConflictedCodes());
        return result;
    }

    @Override
    public Map<String, com.panjia.contracts.dto.EmployeeRef> findEmployeeRefsByNames(Collection<String> names) {
        if (names == null || names.isEmpty()) {
            return Map.of();
        }
        List<Employee> list = employeeMapper.selectList(
            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Employee>()
                .in(Employee::getEmployeeName, names));
        // 重名（同名命中多行）视为歧义不返回，调用方按未匹配处理（含离职员工，历史工资补录）
        Map<String, List<Employee>> byName = new java.util.LinkedHashMap<>();
        for (Employee e : list) {
            byName.computeIfAbsent(e.getEmployeeName(), k -> new java.util.ArrayList<>()).add(e);
        }
        Map<String, com.panjia.contracts.dto.EmployeeRef> map = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, List<Employee>> entry : byName.entrySet()) {
            if (entry.getValue().size() == 1) {
                Employee e = entry.getValue().get(0);
                map.put(entry.getKey(), new com.panjia.contracts.dto.EmployeeRef(e.getEmployeeId(), e.getEmployeeCode()));
            }
        }
        return map;
    }

    // ==================== 内部方法 ====================

    /**
     * 入职初始事实（change_field=ALL，不闭合旧区间）。
     */
    private void initFact(Long employeeId, FactType type, String value, LocalDate effect) {
        SalaryFact fact = new SalaryFact();
        fact.setEmployeeId(employeeId);
        fact.setFactType(type);
        fact.setValue(value != null ? value : NO_MENTOR);
        fact.setEffectiveDate(effect);
        fact.setChangeField(CHANGE_FIELD_ALL);
        salaryFactMapper.insert(fact);
    }

    /**
     * 通用变更入口：闭合旧区间 → 新增事实 → 写日志。分字段独立时间线。
     */
    private void changeFact(Long employeeId, FactType type, String newValue,
                            LocalDate effect, Long operatorId) {
        String oldValue = salaryFactMapper.selectValueAt(employeeId, type, effect);
        salaryFactMapper.closeActiveAt(employeeId, type, effect);
        // 回填生效日早于既有后续事实时（如入职初始化的无伯乐占位），删除被新区间
        // 完全覆盖的开放事实，避免"生效日最新者优先"取数遮蔽新值（2026-10-18 伯乐奖励事故）
        salaryFactMapper.deleteOpenAfter(employeeId, type, effect);

        SalaryFact fact = new SalaryFact();
        fact.setEmployeeId(employeeId);
        fact.setFactType(type);
        fact.setValue(newValue);
        fact.setEffectiveDate(effect);
        fact.setChangeField(type.getCode());
        salaryFactMapper.insert(fact);

        writeLog(employeeId, type.getCode(), oldValue, newValue, effect, operatorId);
    }

    /**
     * 布尔事实变更（新值为 null 表示本次不修改）。
     */
    private void changeBoolFact(Long employeeId, FactType type, Boolean newValue,
                                LocalDate effect, Long operatorId) {
        if (newValue == null) {
            return;
        }
        String target = boolVal(newValue);
        String current = salaryFactMapper.selectValueAt(employeeId, type, effect);
        if (!target.equals(current)) {
            changeFact(employeeId, type, target, effect, operatorId);
        }
    }

    /** 金额事实变更（null=不修改；0/null → 存 null 表示用全局默认） */
    private void changeDecimalFact(Long employeeId, FactType type, java.math.BigDecimal newValue,
                                   LocalDate effect, Long operatorId) {
        if (newValue == null) {
            return;
        }
        String target = decimalVal(newValue);
        String current = salaryFactMapper.selectValueAt(employeeId, type, effect);
        if (!target.equals(current != null ? current : "")) {
            changeFact(employeeId, type, target, effect, operatorId);
        }
    }

    /**
     * 写变更日志。
     */
    private void writeLog(Long employeeId, String changeField, String beforeValue,
                          String afterValue, LocalDate effect, Long operatorId) {
        ChangeLog log = new ChangeLog();
        log.setEmployeeId(employeeId);
        log.setChangeField(changeField);
        log.setBeforeValue(beforeValue);
        log.setAfterValue(afterValue);
        log.setEffectiveDate(effect);
        log.setOperatorId(operatorId);
        changeLogMapper.insert(log);
    }

    /**
     * 按最新事实切片刷新当前态物化记录。
     */
    private void refreshRecord(Employee emp) {
        LocalDate point = LocalDate.now();
        SalaryRecord record = new SalaryRecord();
        record.setEmployeeId(emp.getEmployeeId());
        record.setDeptId(emp.getDeptId());
        record.setStatus(emp.getStatus());
        record.setLevelCode(salaryFactMapper.selectValueAt(emp.getEmployeeId(), FactType.LEVEL, point));
        record.setSocialInsured(parseBool(salaryFactMapper.selectValueAt(emp.getEmployeeId(), FactType.SOCIAL, point)));
        record.setSocialFee(parseDecimal(salaryFactMapper.selectValueAt(emp.getEmployeeId(), FactType.SOCIAL_FEE, point)));
        record.setHousingInsured(parseBool(salaryFactMapper.selectValueAt(emp.getEmployeeId(), FactType.HOUSING, point)));
        record.setCommercialInsured(parseBool(salaryFactMapper.selectValueAt(emp.getEmployeeId(), FactType.COMMERCIAL, point)));
        record.setCommercialFee(parseDecimal(salaryFactMapper.selectValueAt(emp.getEmployeeId(), FactType.COMMERCIAL_FEE, point)));
        record.setDormitory(parseBool(salaryFactMapper.selectValueAt(emp.getEmployeeId(), FactType.DORMITORY, point)));
        record.setHousingFund(parseDecimal(salaryFactMapper.selectValueAt(emp.getEmployeeId(), FactType.HOUSING_FUND, point)));
        record.setDormitoryFee(parseDecimal(salaryFactMapper.selectValueAt(emp.getEmployeeId(), FactType.DORMITORY_FEE, point)));
        record.setIsPartTime(parseBool(salaryFactMapper.selectValueAt(emp.getEmployeeId(), FactType.PARTTIME, point)));
        String mentorValue = salaryFactMapper.selectValueAt(emp.getEmployeeId(), FactType.MENTOR, point);
        record.setMentorEmployeeId(StringUtils.isNotBlank(mentorValue) ? Long.valueOf(mentorValue) : null);
        salaryRecordMapper.upsert(record);
    }

    /**
     * 伯乐工号 → 伯乐员工 ID；空白返回 null；不存在抛异常。
     */
    private Long resolveMentorIdByCode(String mentorCode) {
        if (StringUtils.isBlank(mentorCode)) {
            return null;
        }
        Employee mentor = employeeMapper.selectOne(new LambdaQueryWrapper<Employee>()
            .eq(Employee::getEmployeeCode, mentorCode.trim())
            .last("LIMIT 1"));
        if (mentor == null) {
            throw new ServiceException("伯乐工号 {} 不存在", mentorCode);
        }
        return mentor.getEmployeeId();
    }

    /**
     * 员工实体 → 视图（不含部门名/岗位/算薪态，由 enrich 批量填充）。
     */
    private EmployeeVO toVO(Employee emp) {
        EmployeeVO vo = new EmployeeVO();
        BeanUtil.copyProperties(emp, vo);
        return vo;
    }

    /**
     * 批量填充视图：算薪当前态、部门全路径、岗位名集合、伯乐信息。
     */
    private void enrich(List<EmployeeVO> vos) {
        if (vos.isEmpty()) {
            return;
        }
        List<Long> employeeIds = vos.stream().map(EmployeeVO::getEmployeeId).toList();

        // 算薪当前态
        Map<Long, SalaryRecord> records = new LinkedHashMap<>();
        salaryRecordMapper.selectByIds(employeeIds)
            .forEach(r -> records.put(r.getEmployeeId(), r));
        for (EmployeeVO vo : vos) {
            SalaryRecord record = records.get(vo.getEmployeeId());
            if (record != null) {
                vo.setLevelCode(record.getLevelCode());
                vo.setSocialInsured(record.getSocialInsured());
                vo.setSocialFee(record.getSocialFee());
                vo.setHousingInsured(record.getHousingInsured());
                vo.setHousingFund(record.getHousingFund());
                vo.setCommercialInsured(record.getCommercialInsured());
                vo.setCommercialFee(record.getCommercialFee());
                vo.setDormitory(record.getDormitory());
                vo.setDormitoryFee(record.getDormitoryFee());
                vo.setIsPartTime(record.getIsPartTime());
                if (vo.getMentorEmployeeId() == null) {
                    vo.setMentorEmployeeId(record.getMentorEmployeeId());
                }
            }
        }

        // 部门全路径名
        Set<Long> deptIds = vos.stream().map(EmployeeVO::getDeptId).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, String> deptNames = deptPort.findDeptFullNames(deptIds);
        vos.forEach(vo -> vo.setDeptName(deptNames.get(vo.getDeptId())));

        // 团队业绩排除开关（事实表当前态；低频配置，批量查 fact 不走物化表）
        Map<Long, String> excludeTeamFacts = new LinkedHashMap<>();
        for (SalaryFact fact : salaryFactMapper.selectValuesAt(employeeIds, FactType.EXCLUDE_TEAM, LocalDate.now())) {
            excludeTeamFacts.put(fact.getEmployeeId(), fact.getValue());
        }
        vos.forEach(vo -> vo.setExcludeTeam(
            Boolean.parseBoolean(excludeTeamFacts.get(vo.getEmployeeId()))));

        // 岗位名集合
        List<Long> userIds = vos.stream().map(EmployeeVO::getUserId).filter(Objects::nonNull).toList();
        Map<Long, List<String>> postNames = accountPort.findPostNamesByUserIds(userIds);
        vos.forEach(vo -> vo.setPostNames(
            vo.getUserId() != null ? postNames.getOrDefault(vo.getUserId(), List.of()) : List.of()));

        // 伯乐姓名/工号
        Set<Long> mentorIds = vos.stream().map(EmployeeVO::getMentorEmployeeId).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, Employee> mentors = new LinkedHashMap<>();
        if (!mentorIds.isEmpty()) {
            employeeMapper.selectByIds(mentorIds).forEach(m -> mentors.put(m.getEmployeeId(), m));
        }
        for (EmployeeVO vo : vos) {
            Employee mentor = vo.getMentorEmployeeId() != null ? mentors.get(vo.getMentorEmployeeId()) : null;
            if (mentor != null) {
                vo.setMentorName(mentor.getEmployeeName());
                vo.setMentorCode(mentor.getEmployeeCode());
            }
        }
    }

    /**
     * 变更字段中文名（fact_type 取枚举描述，特殊字段显式命名）。
     */
    private String displayFieldName(String changeField) {
        FactType type = FactType.fromCode(changeField);
        if (type != null) {
            return type.getDesc();
        }
        return switch (changeField) {
            case CHANGE_FIELD_ALL -> "入职初始化";
            case CHANGE_FIELD_DEPT -> "归属部门";
            case CHANGE_FIELD_POSTS -> "岗位/角色";
            case CHANGE_FIELD_EFFECTIVE -> "生效日调整";
            default -> changeField;
        };
    }

    /**
     * MENTOR 事实值（员工 ID）→ 展示文案。
     */
    private String displayMentor(String value) {
        if (StringUtils.isBlank(value)) {
            return "无伯乐";
        }
        Employee mentor = employeeMapper.selectById(Long.valueOf(value));
        return mentor == null ? value : mentor.getEmployeeName() + "（" + mentor.getEmployeeCode() + "）";
    }

    /**
     * 取月份最后一天作为切片点（月内最新事实）。
     */
    private LocalDate monthEnd(LocalDate date) {
        return date.withDayOfMonth(date.lengthOfMonth());
    }

    /**
     * 布尔 → fact 存储值。
     */
    private String boolVal(Boolean value) {
        return Boolean.TRUE.equals(value) ? BOOL_TRUE : BOOL_FALSE;
    }

    /**
     * fact 存储值 → 布尔。
     */
    private Boolean parseBool(String value) {
        return BOOL_TRUE.equals(value);
    }
}
