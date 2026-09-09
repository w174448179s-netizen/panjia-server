package com.panjia.people.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.PageResult;
import org.dromara.common.core.domain.R;
import org.dromara.common.log.annotation.Log;
import org.dromara.common.log.enums.BusinessType;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.common.web.core.BaseController;
import com.panjia.people.dto.ChangeLogVO;
import com.panjia.people.dto.DeptNode;
import com.panjia.people.dto.EmployeeCreateDTO;
import com.panjia.people.dto.EmployeeQuery;
import com.panjia.people.dto.EmployeeUpdateDTO;
import com.panjia.people.dto.EmployeeVO;
import com.panjia.people.dto.PostOption;
import com.panjia.people.port.DeptPort;
import com.panjia.people.port.PostRolePort;
import com.panjia.people.service.EmployeeService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 员工管理（V5.2：主数据 + 算薪事实 + 岗位角色同步）。
 * <p>
 * 无删除接口：离职 = status 改 LEFT（账户禁用，数据保留）。
 */
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/people/employee")
public class EmployeeController extends BaseController {

    private final EmployeeService employeeService;
    private final PostRolePort postRolePort;
    private final DeptPort deptPort;

    /**
     * 分页查询员工列表。
     *
     * @param query     筛选条件
     * @param pageQuery 分页参数
     * @return 员工分页
     */
    @SaCheckPermission("people:employee:list")
    @GetMapping("/list")
    public R<PageResult<EmployeeVO>> list(EmployeeQuery query, PageQuery pageQuery) {
        return R.ok(employeeService.pageList(query, pageQuery));
    }

    /**
     * 员工详情（基本信息 + 算薪配置 + 岗位）。
     *
     * @param employeeId 员工 ID
     * @return 员工详情
     */
    @SaCheckPermission("people:employee:list")
    @GetMapping("/{employeeId}")
    public R<EmployeeVO> getInfo(@PathVariable Long employeeId) {
        return R.ok(employeeService.getDetail(employeeId));
    }

    /**
     * 新增员工（建员工 + 系统账户 + 岗位/角色 + 初始算薪事实）。
     *
     * @param dto 新增请求
     * @return 员工 ID
     */
    @SaCheckPermission("people:employee:add")
    @Log(title = "员工管理", businessType = BusinessType.INSERT)
    @PostMapping
    public R<Long> add(@Validated @RequestBody EmployeeCreateDTO dto) {
        return R.ok("新增成功", employeeService.createEmployee(dto, LoginHelper.getUserId()));
    }

    /**
     * 修改员工（统一 diff：部门/岗位同步系统，职级/开关走事实变更）。
     *
     * @param employeeId 员工 ID
     * @param dto        修改请求
     * @return 结果
     */
    @SaCheckPermission("people:employee:edit")
    @Log(title = "员工管理", businessType = BusinessType.UPDATE)
    @PutMapping("/{employeeId}")
    public R<Void> edit(@PathVariable Long employeeId, @Validated @RequestBody EmployeeUpdateDTO dto) {
        employeeService.updateEmployee(employeeId, dto, LoginHelper.getUserId());
        return R.ok();
    }

    /**
     * 员工变更记录时间线。
     *
     * @param employeeId 员工 ID
     * @return 变更记录
     */
    @SaCheckPermission("people:employee:list")
    @GetMapping("/{employeeId}/history")
    public R<List<ChangeLogVO>> history(@PathVariable Long employeeId) {
        return R.ok(employeeService.getHistory(employeeId));
    }

    /**
     * 岗位选项（职位多选下拉）。
     *
     * @return 岗位选项
     */
    @GetMapping("/postOptions")
    public R<List<PostOption>> postOptions() {
        return R.ok(postRolePort.listAllPosts());
    }

    /**
     * 部门树选项（门店 → 组别，不含客户根节点）。
     *
     * @return 部门树
     */
    @GetMapping("/deptTree")
    public R<List<DeptNode>> deptTree() {
        return R.ok(deptPort.listDeptTree());
    }
}
