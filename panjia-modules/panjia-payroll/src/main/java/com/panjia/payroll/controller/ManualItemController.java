package com.panjia.payroll.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.annotation.SaMode;
import com.panjia.contracts.dto.EmployeeMainDataDTO;
import com.panjia.contracts.port.EmployeeMainDataQueryPort;
import com.panjia.payroll.domain.ManualItem;
import com.panjia.payroll.domain.ManualItemType;
import com.panjia.payroll.dto.ManualItemExportVo;
import com.panjia.payroll.service.ManualItemService;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.dromara.common.excel.utils.ExcelBuilder;
import org.dromara.common.satoken.utils.LoginHelper;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 手工录入项（奖金/其他收入/其他支出）。
 * <p>该接口被「奖金录入」与「其他收支录入」两个菜单共用，故权限码取二者之一的 OR 语义。</p>
 */
@RestController
@RequestMapping("/payroll/manual-item")
@RequiredArgsConstructor
public class ManualItemController {

    private final ManualItemService manualItemService;
    private final EmployeeMainDataQueryPort employeeMainDataQueryPort;

    @SaCheckPermission(value = {"payroll:bonus:list", "payroll:otherearn:list"}, mode = SaMode.OR)
    @PostMapping
    public R<Long> create(@RequestBody ManualItem item) {
        return R.ok(manualItemService.create(item, LoginHelper.getUserId()));
    }

    @SaCheckPermission(value = {"payroll:bonus:list", "payroll:otherearn:list"}, mode = SaMode.OR)
    @GetMapping
    public R<List<ManualItem>> list(@RequestParam String period) {
        return R.ok(manualItemService.listByPeriod(period));
    }

    @SaCheckPermission(value = {"payroll:bonus:list", "payroll:otherearn:list"}, mode = SaMode.OR)
    @GetMapping("/{id}")
    public R<ManualItem> get(@PathVariable Long id) {
        return R.ok(manualItemService.getById(id));
    }

    @SaCheckPermission(value = {"payroll:bonus:list", "payroll:otherearn:list"}, mode = SaMode.OR)
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        manualItemService.delete(id, LoginHelper.getUserId());
        return R.ok();
    }

    /** 导入模板下载（仅表头，截止月留空=长期生效） */
    @SaCheckPermission(value = {"payroll:bonus:list", "payroll:otherearn:list"}, mode = SaMode.OR)
    @PostMapping("/importTemplate")
    public void importTemplate(HttpServletResponse response) {
        ExcelBuilder.of(new ArrayList<>(), com.panjia.payroll.dto.ManualItemImportVo.class)
            .sheetName("其他收支导入").toResponse(response);
    }

    /** 导出某生效月的手工项（与列表同口径） */
    @SaCheckPermission(value = {"payroll:bonus:list", "payroll:otherearn:list"}, mode = SaMode.OR)
    @PostMapping("/export")
    public void export(@RequestParam String period, HttpServletResponse response) {
        List<ManualItem> items = manualItemService.listByPeriod(period);
        // 入口一次批量回填员工编码/姓名
        Map<Long, EmployeeMainDataDTO> empMap = items.isEmpty() ? Map.of()
            : employeeMainDataQueryPort.listByIds(
                items.stream().map(ManualItem::getEmployeeId).collect(Collectors.toSet()));
        List<ManualItemExportVo> rows = items.stream().map(it -> {
            ManualItemExportVo vo = new ManualItemExportVo();
            EmployeeMainDataDTO emp = empMap.get(it.getEmployeeId());
            vo.setEmployeeCode(emp == null ? String.valueOf(it.getEmployeeId()) : emp.getEmployeeCode());
            vo.setEmployeeName(emp == null ? "" : emp.getEmployeeName());
            vo.setItemTypeLabel(ManualItemType.OTHER_INCOME == it.getItemType() ? "收入"
                : ManualItemType.OTHER_DEDUCT == it.getItemType() ? "支出" : it.getItemType().getCode());
            vo.setSubType(it.getSubType());
            vo.setAmount(it.getAmount());
            vo.setStartMonth(it.getStartMonth());
            vo.setEndMonth(it.getEndMonth() == null ? "长期" : it.getEndMonth());
            vo.setReason(it.getReason());
            vo.setCreateTime(String.valueOf(it.getCreateTime()));
            return vo;
        }).collect(Collectors.toList());
        ExcelBuilder.of(rows, ManualItemExportVo.class).sheetName("其他收支").toResponse(response);
    }

    /** Excel 批量导入（整批校验通过才入库，任一行有错整体拒绝） */
    @SaCheckPermission(value = {"payroll:bonus:list", "payroll:otherearn:list"}, mode = SaMode.OR)
    @PostMapping(value = "/importData", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public R<String> importData(@RequestPart("file") MultipartFile file) throws Exception {
        var result = ExcelBuilder.read(file.getInputStream(), com.panjia.payroll.dto.ManualItemImportVo.class)
            .validate(true)
            .doRead();
        return R.ok(manualItemService.batchImport(result.getList(), LoginHelper.getUserId()));
    }
}
