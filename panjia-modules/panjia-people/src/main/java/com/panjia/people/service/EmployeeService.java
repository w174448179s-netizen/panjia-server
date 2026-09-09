package com.panjia.people.service;

import com.panjia.contracts.port.PeopleQueryPort;
import com.panjia.people.dto.ChangeLogVO;
import com.panjia.people.dto.EmployeeCreateDTO;
import com.panjia.people.dto.EmployeeQuery;
import com.panjia.people.dto.EmployeeUpdateDTO;
import com.panjia.people.dto.EmployeeVO;
import org.dromara.common.core.domain.PageResult;
import org.dromara.common.mybatis.core.page.PageQuery;

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
     * 员工变更记录时间线（按生效日倒序）。
     *
     * @param employeeId 员工 ID
     * @return 变更记录视图
     */
    List<ChangeLogVO> getHistory(Long employeeId);
}
