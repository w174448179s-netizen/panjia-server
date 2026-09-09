package com.panjia.people.application;

import com.panjia.contracts.port.EmployeeQueryPort;
import com.panjia.contracts.snapshot.EmployeeSnapshot;
import com.panjia.people.domain.Employee;
import com.panjia.people.domain.service.SnapshotFactory;
import com.panjia.people.infrastructure.repository.ChangeLogMapper;
import com.panjia.people.infrastructure.repository.EmployeeMapper;
import com.panjia.people.infrastructure.repository.MentorRelationMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 员工快照供给服务 —— 外部域获取员工信息的唯一入口（实现 {@link EmployeeQueryPort}）。
 * <p>
 * 🚨 payroll/commission 等域只通过此服务获取员工历史状态。
 * <p>
 * 取数逻辑契约（§5.2）：
 * <ol>
 *   <li>校验员工存在，否则抛 EMPLOYEE_NOT_FOUND</li>
 *   <li>按 effective_from 倒序取第一条覆盖时点的职级；无记录抛 NO_VALID_LEVEL（不降级）</li>
 *   <li>社保/师徒同理取生效记录</li>
 *   <li>组装内存 DTO，people 域不持久化快照</li>
 * </ol>
 */
@Slf4j
@Service
@Transactional(readOnly = true)
public class EmployeeSnapshotService implements EmployeeQueryPort {

    @Autowired
    private EmployeeMapper employeeMapper;
    @Autowired
    private EmployeeService employeeService;
    @Autowired
    private ChangeLogMapper changeLogMapper;
    @Autowired
    private MentorRelationMapper mentorRelationMapper;

    @Override
    public EmployeeSnapshot takeSnapshot(Long employeeId, Date pointInTime) {
        LocalDate point = toLocalDate(pointInTime);
        return buildSnapshotWithRole(employeeId, point);
    }

    @Override
    public Map<Long, EmployeeSnapshot> takeSnapshots(List<Long> employeeIds, Date pointInTime) {
        LocalDate point = toLocalDate(pointInTime);
        return employeeIds.stream().collect(Collectors.toMap(
            id -> id,
            id -> buildSnapshotWithRole(id, point),
            (first, duplicate) -> first));
    }

    @Override
    public List<EmployeeSnapshot> takeSnapshotsByDept(Long deptId, Date pointInTime) {
        LocalDate point = toLocalDate(pointInTime);
        List<Long> employeeIds = employeeMapper.selectActiveIdsByDept(deptId, point);
        return employeeIds.stream()
            .map(id -> buildSnapshotWithRole(id, point))
            .collect(Collectors.toList());
    }

    /**
     * 构建快照：SnapshotFactory 生成基础快照后，用变更日志覆盖角色为时点值，并补充合格徒弟数。
     *
     * @param employeeId  员工 ID
     * @param point        快照时点
     * @return 带时点角色和合格徒弟数的快照
     */
    private EmployeeSnapshot buildSnapshotWithRole(Long employeeId, LocalDate point) {
        Employee employee = employeeService.loadFullEmployee(employeeId);
        EmployeeSnapshot snapshot = SnapshotFactory.create(employee, point);
        // 角色取时点值：查变更日志中该时点前最近一次 ROLE_CHANGE 的新值
        String roleAtPoint = changeLogMapper.selectRoleAtPoint(employeeId, point.atStartOfDay());
        if (roleAtPoint != null) {
            snapshot.setRole(roleAtPoint);
        }
        // 合格徒弟数（师傅视角，>=2 年行业经验且有效）
        long count = mentorRelationMapper.countQualifiedByMentor(employeeId);
        snapshot.setQualifiedApprenticeCount((int) count);
        return snapshot;
    }

    /**
     * 将 java.util.Date 转为 LocalDate（系统时区）。
     *
     * @param date 时间点
     * @return 日期
     */
    private LocalDate toLocalDate(Date date) {
        return date.toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
    }
}
