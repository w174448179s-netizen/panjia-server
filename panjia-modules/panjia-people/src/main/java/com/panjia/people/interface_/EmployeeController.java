package com.panjia.people.interface_;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.panjia.contracts.exception.BizCode;
import com.panjia.contracts.snapshot.EmployeeSnapshot;
import com.panjia.people.application.EmployeeService;
import com.panjia.people.application.EmployeeSnapshotService;
import com.panjia.people.application.dto.EmployeeCreateDTO;
import com.panjia.people.application.dto.EmployeeDTO;
import com.panjia.people.application.dto.EmployeeUpdateDTO;
import com.panjia.people.domain.Employee;
import com.panjia.people.interface_.converter.EmployeeConverter;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.domain.PageResult;
import org.dromara.common.core.domain.R;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.Date;

/**
 * 员工档案控制器。
 */
@Slf4j
@RestController
@RequestMapping("/people/employee")
public class EmployeeController {

    @Autowired
    private EmployeeService employeeService;
    @Autowired
    private EmployeeSnapshotService snapshotService;

    /**
     * 创建员工（建档 + 初始职级 + 社保）。
     *
     * @param dto 创建 DTO
     * @return 员工 ID
     */
    @SaCheckPermission("people:employee:add")
    @PostMapping
    public R<Long> create(@Validated @RequestBody EmployeeCreateDTO dto) {
        return R.ok(employeeService.createEmployee(dto));
    }

    /**
     * 更新员工基础档案。
     *
     * @param id  员工 ID
     * @param dto 更新 DTO
     * @return 操作结果
     */
    @SaCheckPermission("people:employee:edit")
    @PutMapping("/{id}")
    public R<Void> update(@PathVariable Long id, @Validated @RequestBody EmployeeUpdateDTO dto) {
        employeeService.updateEmployee(id, dto);
        return R.ok();
    }

    /**
     * 员工离职（关闭职级 + 失效师徒关系）。
     *
     * @param id         员工 ID
     * @param resignDate 离职日期
     * @return 操作结果
     */
    @SaCheckPermission("people:employee:resign")
    @PostMapping("/{id}/resign")
    public R<Void> resign(@PathVariable Long id,
                          @RequestParam @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate resignDate) {
        employeeService.resign(id, resignDate);
        return R.ok();
    }

    /**
     * 分页查询员工档案。
     *
     * @param pageQuery 分页参数
     * @param deptId    部门筛选
     * @param role      角色筛选
     * @return 分页结果
     */
    @SaCheckPermission("people:employee:list")
    @GetMapping("/page")
    public R<PageResult<EmployeeDTO>> page(PageQuery pageQuery,
                                           @RequestParam(required = false) Long deptId,
                                           @RequestParam(required = false) String role) {
        return R.ok(employeeService.page(pageQuery, deptId, role));
    }

    /**
     * 按工号查询员工。
     *
     * @param code 工号
     * @return 员工 DTO
     */
    @SaCheckPermission("people:employee:list")
    @GetMapping("/by-code/{code}")
    public R<EmployeeDTO> getByCode(@PathVariable String code) {
        Employee employee = employeeService.findByEmployeeCode(code);
        if (employee == null) {
            throw new ServiceException("员工不存在: " + code, BizCode.EMPLOYEE_NOT_FOUND);
        }
        return R.ok(EmployeeConverter.toDTO(employee));
    }

    /**
     * 查询员工在某时点的快照（外部域/前端核对历史薪酬用）。
     *
     * @param id          员工 ID
     * @param pointInTime 时点（yyyy-MM-dd）
     * @return 员工快照
     */
    @SaCheckPermission("people:employee:list")
    @GetMapping("/snapshot/{id}")
    public R<EmployeeSnapshot> snapshot(@PathVariable Long id,
                                        @RequestParam @DateTimeFormat(pattern = "yyyy-MM-dd") Date pointInTime) {
        return R.ok(snapshotService.takeSnapshot(id, pointInTime));
    }
}
