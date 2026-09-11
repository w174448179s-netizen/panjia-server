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
 * TODO（V2.1 跨域扩 port 专项）：
 * <ul>
 *   <li>PeopleQueryPort.getSnapshotAt 返回 Map&lt;fact_type, value&gt;（8 类算薪事实：DEPT/POST/GRADE 等），
 *       与 {@link EmployeeSnapshotDTO} 字段（employeeId/employeeCode/employeeName/deptId/deptName/status/userId）不对齐</li>
 *   <li>需在 panjia-contracts 新增 {@code EmployeeMainDataQueryPort}（按 id/code 批量查员工主数据），
 *       由 panjia-people EmployeeService 实现</li>
 *   <li>本适配器实现 getByEmployeeId / getByEmployeeCode / listByDeptId 时：
 *       先调 EmployeeMainDataQueryPort 拿主数据，再用 PeopleQueryPort.getSnapshotAt 拿算薪快照取数日</li>
 * </ul>
 * <p>
 * 在 EmployeeMainDataQueryPort 落地前，本适配器三个方法均抛 {@link UnsupportedOperationException}。
 * PerformanceEngine.buildSingleFact 现有 catch 分支可兜底（员工字段留空，事实仍生成）。
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
