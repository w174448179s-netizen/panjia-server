package com.panjia.people.service;

import com.panjia.contracts.port.PeopleQueryPort;
import com.panjia.people.dto.ChangeLogVO;
import com.panjia.people.dto.EmployeeCreateDTO;
import com.panjia.people.dto.EmployeeQuery;
import com.panjia.people.dto.EmployeeUpdateDTO;
import com.panjia.people.dto.EmployeeVO;
import com.panjia.people.dto.VirtualEmployeeCandidateVO;
import com.panjia.people.dto.VirtualEmployeeInitDTO;
import com.panjia.people.dto.VirtualEmployeeInitResultVO;
import org.dromara.common.core.domain.PageResult;
import org.dromara.common.mybatis.core.page.PageQuery;

import java.time.LocalDate;
import java.util.List;

/**
 * 员工服务（V5.2：主数据 + 算薪事实 + 账户岗位角色同步）。
 * <p>
 * 同时实现 {@link PeopleQueryPort} 供 payroll 等下游域按月份取算薪快照。
 */
public interface EmployeeService extends PeopleQueryPort {

    /**
     * 分页查询员工列表（筛选：工号/姓名/部门树/岗位/状态）。
     *
     * @param query     筛选条件
     * @param pageQuery 分页参数
     * @return 员工视图分页
     */
    PageResult<EmployeeVO> pageList(EmployeeQuery query, PageQuery pageQuery);

    /**
     * 导出用全量查询（不分页）：按筛选条件返回匹配员工，供 Excel 导出。
     * <p>复用 {@link #pageList} 的筛选与排序口径，仅去掉分页上限。
     *
     * @param query 筛选条件
     * @return 员工视图列表
     */
    List<EmployeeVO> listForExport(EmployeeQuery query);

    /**
     * 员工详情（基本信息 + 算薪当前态 + 岗位）。
     *
     * @param employeeId 员工 ID
     * @return 员工视图
     */
    EmployeeVO getDetail(Long employeeId);

    /**
     * 新增员工：建员工 + 建账户（岗位/角色）+ 8 条初始 fact + 入职日志。
     *
     * @param dto        新增请求
     * @param operatorId 操作人 ID
     * @return 新建员工 ID
     */
    Long createEmployee(EmployeeCreateDTO dto, Long operatorId);

    /**
     * 修改员工（★统一 diff：部门/岗位变更同步 sys_user，职级/开关走 fact）。
     *
     * @param employeeId 员工 ID
     * @param dto        修改请求
     * @param operatorId 操作人 ID
     */
    void updateEmployee(Long employeeId, EmployeeUpdateDTO dto, Long operatorId);

    /**
     * 调整算薪事实生效日期（值不变，仅平移生效日；用于更正录入时选错生效日的场景）。
     * <p>
     * 按变更项精确调整：传单个/多个 factType 码（如 MENTOR/LEVEL/SOCIAL），
     * 或传 ["ALL"] 调整全部事实项。已是指定日期的项自动跳过。
     *
     * @param employeeId 员工 ID
     * @param factTypes  变更项码列表（FactType code；["ALL"]=全部项）
     * @param newDate    新生效日期
     * @param operatorId 操作人 ID
     * @return 实际调整的项数
     */
    int adjustFactDates(Long employeeId, List<String> factTypes, LocalDate newDate, Long operatorId);

    /**
     * 员工变更记录时间线（按生效日倒序）。
     *
     * @param employeeId 员工 ID
     * @return 变更记录视图
     */
    List<ChangeLogVO> getHistory(Long employeeId);

    /**
     * 店组虚拟人候选列表：全部启用部门 + 店组编码 + 是否已有虚拟人。
     *
     * @return 候选行列表（按部门 ID 升序）
     */
    List<VirtualEmployeeCandidateVO> listVirtualCandidates();

    /**
     * 一键新建店组虚拟人：先写入店组编码（sys_dept.dept_category），
     * 再按「工号 = 店组编码、姓名 = 部门名」幂等补建虚拟人（不建登录账户/岗位/算薪事实）。
     *
     * @param items      待建项（部门 + 店组编码）
     * @param operatorId 操作人 ID
     * @return 新建/跳过/冲突结果
     */
    VirtualEmployeeInitResultVO initVirtualEmployees(List<VirtualEmployeeInitDTO> items, Long operatorId);
}
