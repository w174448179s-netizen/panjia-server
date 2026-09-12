package com.panjia.performance.adapter;

import com.panjia.contracts.dto.EmployeeMainDataDTO;
import com.panjia.contracts.port.EmployeeMainDataQueryPort;
import com.panjia.contracts.snapshot.EmployeeSnapshot;
import com.panjia.performance.port.EmployeeSnapshotQueryPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 员工快照查询适配器（performance 域 {@link EmployeeSnapshotQueryPort} 的实现）。
 * <p>
 * 委托 contracts 的 {@link EmployeeMainDataQueryPort}（panjia-people 实现）取员工主数据
 * （工号/姓名/部门/账户），装配为 contracts {@link EmployeeSnapshot}——业绩事实构建只需要
 * <b>当前态身份归属</b>，不加载职级/参保时点事实（字段留 null，语义见端口 Javadoc）。
 * <p>
 * 月末完整事实切片是算薪场景（payroll/commission 冻结快照），走
 * {@code PeopleQueryPort.getEmployeeSnapshot}，与本适配器的当前身份视图互不混用。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PeopleSnapshotAdapter implements EmployeeSnapshotQueryPort {

    private final EmployeeMainDataQueryPort employeeMainDataQueryPort;

    @Override
    public EmployeeSnapshot getByEmployeeId(Long employeeId) {
        return toSnapshot(employeeMainDataQueryPort.getByEmployeeId(employeeId));
    }

    @Override
    public EmployeeSnapshot getByEmployeeCode(String employeeCode) {
        return toSnapshot(employeeMainDataQueryPort.getByEmployeeCode(employeeCode));
    }

    @Override
    public List<EmployeeSnapshot> listByDeptId(Long deptId) {
        // 主数据端口未提供部门维度查询，当前无消费方；需要时在 EmployeeMainDataQueryPort 扩展 listByDeptIds
        throw new UnsupportedOperationException("listByDeptId 暂未实现（无消费方）");
    }

    /**
     * 主数据 DTO → contracts 快照（仅身份字段；当前态视图 snapshotDate=当天）。
     */
    private EmployeeSnapshot toSnapshot(EmployeeMainDataDTO main) {
        if (main == null) {
            return null;
        }
        EmployeeSnapshot snapshot = new EmployeeSnapshot();
        snapshot.setEmployeeId(main.getEmployeeId());
        snapshot.setEmployeeCode(main.getEmployeeCode());
        snapshot.setEmployeeName(main.getEmployeeName());
        snapshot.setDeptId(main.getDeptId());
        snapshot.setUserId(main.getUserId());
        snapshot.setSnapshotDate(LocalDate.now());
        snapshot.setSnapshottedAt(LocalDateTime.now());
        return snapshot;
    }
}
