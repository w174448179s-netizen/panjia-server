package com.panjia.people.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.panjia.people.dto.ContractCreateDTO;
import com.panjia.people.dto.ContractExpiringVO;
import com.panjia.people.dto.ContractQuery;
import com.panjia.people.dto.ContractUpdateDTO;
import com.panjia.people.dto.ContractVO;
import com.panjia.people.service.ContractService;
import com.panjia.people.service.ContractTemplateService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.domain.PageResult;
import org.dromara.common.core.domain.R;
import org.dromara.common.log.annotation.Log;
import org.dromara.common.log.enums.BusinessType;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.common.web.core.BaseController;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 员工合同管理（劳动合同/兼职合同/其他）。
 * <p>
 * 续签 = 新增记录，历史保留；到期提前 30 天提醒（{@code GET /expiring}），
 * 供员工管理列表顶部提醒条与合同页共用。
 */
@Slf4j
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/people/contract")
public class ContractController extends BaseController {

    private final ContractService contractService;
    private final ContractTemplateService contractTemplateService;

    /**
     * 分页查询合同列表。
     *
     * @param query     筛选条件
     * @param pageQuery 分页参数
     * @return 合同分页
     */
    @SaCheckPermission("people:contract:list")
    @GetMapping("/list")
    public R<PageResult<ContractVO>> list(ContractQuery query, PageQuery pageQuery) {
        return R.ok(contractService.pageList(query, pageQuery));
    }

    /**
     * 合同到期提醒名单（30 天内到期 + 已到期未重签）。
     *
     * @return 到期名单（已到期在前）
     */
    @SaCheckPermission("people:contract:list")
    @GetMapping("/expiring")
    public R<List<ContractExpiringVO>> expiring() {
        return R.ok(contractService.listExpiring());
    }

    /**
     * 新增合同（续签也是新增记录）。
     *
     * @param dto 新增请求
     * @return 合同 ID
     */
    @SaCheckPermission("people:contract:add")
    @Log(title = "员工合同", businessType = BusinessType.INSERT)
    @PostMapping
    public R<Long> add(@Validated @RequestBody ContractCreateDTO dto) {
        return R.ok("新增成功", contractService.createContract(dto));
    }

    /**
     * 修改合同（错录修正；换人走删除重录）。
     *
     * @param contractId 合同 ID
     * @param dto        修改请求
     * @return 结果
     */
    @SaCheckPermission("people:contract:edit")
    @Log(title = "员工合同", businessType = BusinessType.UPDATE)
    @PutMapping("/{contractId}")
    public R<Void> edit(@PathVariable Long contractId, @RequestBody ContractUpdateDTO dto) {
        contractService.updateContract(contractId, dto);
        return R.ok();
    }

    /**
     * 删除合同（错录修正，支持批量）。
     *
     * @param contractIds 合同 ID 集合（逗号分隔）
     * @return 结果
     */
    @SaCheckPermission("people:contract:delete")
    @Log(title = "员工合同", businessType = BusinessType.DELETE)
    @DeleteMapping("/{contractIds}")
    public R<Void> remove(@PathVariable Long[] contractIds) {
        contractService.deleteContracts(List.of(contractIds));
        return R.ok();
    }

    // ==================== 合同自定义模板 ====================

    /**
     * 查询全部合同模板配置（含未自定义的类型，标记 useDefault=true）。
     */
    @SaCheckPermission("people:contract:list")
    @GetMapping("/templates")
    public R<List<ContractTemplateService.ContractTemplateVO>> listTemplates() {
        return R.ok(contractTemplateService.listAll());
    }

    /**
     * 保存/更新某类型的自定义模板。
     *
     * @param contractType 合同类型 LABOR/PARTTIME/OTHER
     * @param ossId        OSS 文件ID
     */
    @SaCheckPermission("people:contract:edit")
    @Log(title = "合同模板", businessType = BusinessType.UPDATE)
    @PutMapping("/template/{contractType}/{ossId}")
    public R<Void> saveTemplate(@PathVariable String contractType, @PathVariable Long ossId) {
        contractTemplateService.saveTemplate(contractType, ossId, LoginHelper.getUserId());
        return R.ok();
    }

    /**
     * 删除某类型的自定义模板（恢复默认）。
     *
     * @param contractType 合同类型 LABOR/PARTTIME/OTHER
     */
    @SaCheckPermission("people:contract:edit")
    @Log(title = "合同模板", businessType = BusinessType.DELETE)
    @DeleteMapping("/template/{contractType}")
    public R<Void> deleteTemplate(@PathVariable String contractType) {
        contractTemplateService.deleteTemplate(contractType);
        return R.ok();
    }
}
