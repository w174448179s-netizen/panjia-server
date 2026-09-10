package com.panjia.people.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.domain.PageResult;
import org.dromara.common.core.domain.R;
import org.dromara.common.log.annotation.Log;
import org.dromara.common.log.enums.BusinessType;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.common.web.core.BaseController;
import com.panjia.people.domain.PeopleImportBatch;
import com.panjia.people.domain.PeopleImportIssue;
import com.panjia.people.dto.ChangeLogVO;
import com.panjia.people.dto.DeptNode;
import com.panjia.people.dto.EmployeeCreateDTO;
import com.panjia.people.dto.EmployeeQuery;
import com.panjia.people.dto.EmployeeUpdateDTO;
import com.panjia.people.dto.EmployeeVO;
import com.panjia.people.dto.PostOption;
import com.panjia.people.port.DeptPort;
import com.panjia.people.port.PostRolePort;
import com.panjia.people.service.EmployeeImportService;
import com.panjia.people.service.EmployeeService;
import com.panjia.people.service.PeopleImportTemplateBridge;
import com.panjia.importutil.export.TemplateExporter;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 员工管理（V6.0：主数据 + 算薪事实 + 岗位角色同步 + 员工导入）。
 * <p>
 * 无删除接口：离职 = status 改 LEFT（账户禁用，数据保留）。
 * 员工导入 V6.0 回迁本域：两阶段诊断 + 单一大事务落地，复用 common-import-util 工具层。
 */
@Slf4j
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/people/employee")
public class EmployeeController extends BaseController {

    private final EmployeeService employeeService;
    private final EmployeeImportService employeeImportService;
    private final PeopleImportTemplateBridge templateBridge;
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

    /**
     * 员工导入（V6.0 回迁本域）：上传 Excel/CSV，两阶段执行。
     * <p>
     * 阶段 A 诊断（落批次/原始行/问题清单）；诊断通过后阶段 B 单一大事务落地，
     * 任一行失败整批回滚。成功或失败均返回批次 ID，问题清单走
     * {@code GET /people/employee/import/{batchId}/issues} 查询。
     *
     * @param file 导入文件（XLSX/CSV）
     * @return 导入批次 ID
     */
    @SaCheckPermission("people:employee:import")
    @Log(title = "员工导入", businessType = BusinessType.IMPORT)
    @PostMapping("/import")
    public R<Long> importEmployees(@RequestParam("file") MultipartFile file) {
        try {
            Long batchId = employeeImportService.importEmployees(
                file.getBytes(), file.getOriginalFilename(), LoginHelper.getUserId());
            return R.ok("导入完成", batchId);
        } catch (Exception e) {
            log.error("员工导入失败", e);
            return R.fail("员工导入失败: " + e.getMessage());
        }
    }

    /**
     * 员工导入批次列表。
     *
     * @return 批次集合
     */
    @SaCheckPermission("people:employee:import")
    @GetMapping("/import/batches")
    public R<List<PeopleImportBatch>> importBatches() {
        return R.ok(employeeImportService.listBatches());
    }

    /**
     * 员工导入批次问题清单。
     *
     * @param batchId 批次 ID
     * @return 问题集合
     */
    @SaCheckPermission("people:employee:import")
    @GetMapping("/import/{batchId}/issues")
    public R<List<PeopleImportIssue>> importIssues(@PathVariable Long batchId) {
        return R.ok(employeeImportService.listIssues(batchId));
    }

    /**
     * 下载员工导入模板（根据 EMPLOYEE 模板表生成 Excel，含表头 + 示例行）。
     *
     * @param response HTTP 响应
     */
    @SaCheckPermission("people:employee:import")
    @GetMapping("/import/template")
    public void downloadImportTemplate(HttpServletResponse response) {
        com.panjia.importutil.template.model.ImportTemplate template =
            templateBridge.resolve("EMPLOYEE");
        int colCount = template.getColumns() == null ? 0 : template.getColumns().size();
        log.info("员工导入模板: code={}, columns={}", template.getTemplateCode(), colCount);
        byte[] excel = TemplateExporter.toExcel(template);
        log.info("Excel 模板生成: {} bytes", excel.length);
        String fileName = URLEncoder.encode("员工导入模板.xlsx", StandardCharsets.UTF_8);
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setHeader("Content-Disposition", "attachment; filename=\"" + fileName + "\"");
        response.setHeader("Cache-Control", "no-cache, no-store, must-revalidate");
        response.setHeader("Pragma", "no-cache");
        response.setHeader("Expires", "0");
        response.setContentLength(excel.length);
        try (OutputStream out = response.getOutputStream()) {
            out.write(excel);
            out.flush();
        } catch (IOException e) {
            log.warn("员工导入模板下载写入失败", e);
        }
    }
}
