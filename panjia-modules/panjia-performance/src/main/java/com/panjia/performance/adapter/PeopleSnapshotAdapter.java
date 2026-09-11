package com.panjia.performance.adapter;

import com.panjia.contracts.dto.EmployeeMainDataDTO;
import com.panjia.contracts.port.EmployeeMainDataQueryPort;
import com.panjia.performance.dto.EmployeeSnapshotDTO;
import com.panjia.performance.port.EmployeeSnapshotQueryPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 员工快照查询适配器（contracts {@link EmployeeSnapshotQueryPort} 的 performance 侧壳）。
 * <p>
 * 委托 contracts 的 {@link EmployeeMainDataQueryPort}（panjia-people 实现）拿员工主数据
 * （姓名/部门/状态/账户），转为本域 {@link EmployeeSnapshotDTO}。
 * <p>
 * 算薪快照（取数日口径的 8 类事实）仍走 PeopleQueryPort.getSnapshotAt，
 * 待 V2.1 快照合并专项后与本适配器整合。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PeopleSnapshotAdapter implements EmployeeSnapshotQueryPort {

    private final EmployeeMainDataQueryPort employeeMainDataQueryPort;

    @Override
    public EmployeeSnapshotDTO getByEmployeeId(Long employeeId) {
        return toSnapshot(employeeMainDataQueryPort.getByEmployeeId(employeeId));
    }

    @Override
    public EmployeeSnapshotDTO getByEmployeeCode(String employeeCode) {
        return toSnapshot(employeeMainDataQueryPort.getByEmployeeCode(employeeCode));
    }

    @Override
    public List<EmployeeSnapshotDTO> listByDeptId(Long deptId) {
        // 主数据端口未提供部门维度查询，当前无消费方；需要时在 EmployeeMainDataQueryPort 扩展 listByDeptIds
        throw new UnsupportedOperationException("listByDeptId 暂未实现（无消费方）");
    }

    private EmployeeSnapshotDTO toSnapshot(EmployeeMainDataDTO main) {
        if (main == null) {
            return null;
        }
        EmployeeSnapshotDTO snapshot = new EmployeeSnapshotDTO();
        snapshot.setEmployeeId(main.getEmployeeId());
        snapshot.setEmployeeCode(main.getEmployeeCode());
        snapshot.setEmployeeName(main.getEmployeeName());
        snapshot.setDeptId(main.getDeptId());
        snapshot.setDeptName(main.getDeptName());
        snapshot.setStatus(main.getStatus());
        snapshot.setUserId(main.getUserId());
        return snapshot;
    }
}
