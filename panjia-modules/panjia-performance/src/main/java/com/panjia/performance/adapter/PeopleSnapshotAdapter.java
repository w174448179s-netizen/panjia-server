package com.panjia.performance.adapter;

import com.panjia.contracts.port.PeopleQueryPort;
import com.panjia.performance.dto.EmployeeSnapshotDTO;
import com.panjia.performance.port.EmployeeSnapshotQueryPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 员工快照查询适配器。
 * <p>
 * 通过 panjia-contracts 中的 {@link PeopleQueryPort} 跨域查询员工信息。
 * <p>
 * 注：当前 PeopleQueryPort 提供算薪事实快照能力，与 EmployeeSnapshotDTO 所需的
 * 员工主数据字段（部门、姓名、状态等）尚未完全对齐，因此本适配器先以
 * {@link UnsupportedOperationException} 占位，待 people 域补充对应查询端口后再完善实现。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PeopleSnapshotAdapter implements EmployeeSnapshotQueryPort {

    private final PeopleQueryPort peopleQueryPort;

    @Override
    public EmployeeSnapshotDTO getByEmployeeId(Long employeeId) {
        throw new UnsupportedOperationException("跨域查询暂未实现");
    }

    @Override
    public EmployeeSnapshotDTO getByEmployeeCode(String employeeCode) {
        throw new UnsupportedOperationException("跨域查询暂未实现");
    }

    @Override
    public List<EmployeeSnapshotDTO> listByDeptId(Long deptId) {
        throw new UnsupportedOperationException("跨域查询暂未实现");
    }
}
