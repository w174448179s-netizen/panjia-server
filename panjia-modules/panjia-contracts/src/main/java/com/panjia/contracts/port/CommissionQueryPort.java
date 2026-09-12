package com.panjia.contracts.port;

import com.panjia.contracts.dto.CommissionItemDTO;

import java.util.List;

/**
 * 结佣查询跨域端口（commission 域对外契约，对 payroll 唯一出口）。
 * <p>
 * 架构约束（V1.8 §2.3）：payroll 只依赖 people + commission，不穿透依赖 performance。
 * 新签业绩（PERF_EXPECT）由结佣域<b>只读透传</b>（本域不折算、不加工），保证依赖图不破。
 * <p>
 * 约束：
 * <ul>
 *   <li>①② 只返回 {@code status = APPROVED} 的结佣明细（审批锁定后才可进工资）；</li>
 *   <li>③④ 返回业绩域 PERF_EXPECT 事实的原样透传值，DTO 必带 {@code bizType}（CI C15）；</li>
 *   <li>⑤ 返回全量（含 REVERSED），仅供审计对账；</li>
 *   <li>返回只读 DTO，payroll 禁直连 {@code pj_commission_*} 表。</li>
 * </ul>
 */
public interface CommissionQueryPort {

    /**
     * 按期间 + 门店查已审批（APPROVED）结佣明细。
     *
     * @param period 业绩归属月 YYYY-MM
     * @param deptId 门店 ID
     * @return 结佣明细列表
     */
    List<CommissionItemDTO> findLocked(String period, Long deptId);

    /**
     * 按期间 + 员工查已审批（APPROVED）结佣明细。
     *
     * @param period     业绩归属月 YYYY-MM
     * @param employeeId 员工 ID
     * @return 结佣明细列表
     */
    List<CommissionItemDTO> findLockedByEmployee(String period, Long employeeId);

    /**
     * 按期间 + 门店查新签业绩（PERF_EXPECT，只读透传，未折算）。
     *
     * @param period 业绩归属月 YYYY-MM
     * @param deptId 门店 ID
     * @return 新签业绩列表（原样透传）
     */
    List<CommissionItemDTO> findNewSignByDept(String period, Long deptId);

    /**
     * 按期间 + 员工查新签业绩（PERF_EXPECT，只读透传，未折算）。
     *
     * @param period     业绩归属月 YYYY-MM
     * @param employeeId 员工 ID
     * @return 新签业绩列表（原样透传）
     */
    List<CommissionItemDTO> findNewSignByEmployee(String period, Long employeeId);

    /**
     * 按申请单查明细（含 REVERSED，仅供审计对账）。
     *
     * @param applicationId 申请单 ID
     * @return 结佣明细全量列表
     */
    List<CommissionItemDTO> findByApplication(Long applicationId);
}
