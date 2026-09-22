package com.panjia.performance.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.panjia.contracts.dto.EmployeeMainDataDTO;
import com.panjia.performance.domain.bo.ContractVoidBo;
import com.panjia.performance.domain.bo.PerformanceFactBo;
import com.panjia.performance.domain.bo.PerformanceManageContractDetailBo;
import com.panjia.performance.domain.bo.PerformanceManageContractBo;
import com.panjia.performance.domain.vo.PerformanceFactVo;
import com.panjia.performance.domain.vo.PerformanceFactSearchVo;
import com.panjia.performance.domain.vo.PerformanceManageContractVo;
import com.panjia.performance.domain.vo.PerformanceManageVo;
import com.panjia.performance.domain.vo.PerformanceManagePageVo;
import com.panjia.performance.domain.vo.PerformanceSearchDetailVo;
import com.panjia.performance.domain.bo.PerformanceSearchBizTypesBo;
import com.panjia.performance.domain.bo.PerformanceSearchDetailBo;
import com.panjia.performance.domain.bo.PerformanceSearchEmployeeOptionsBo;
import com.panjia.performance.domain.bo.PerformanceSearchBo;
import com.panjia.performance.service.PerformanceFactVoidService;
import com.panjia.performance.service.IPerformanceQueryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.domain.PageResult;
import org.dromara.common.core.domain.R;
import org.dromara.common.log.annotation.Log;
import org.dromara.common.log.enums.BusinessType;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.web.core.BaseController;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 业绩事实管理。
 * <p>
 * 提供业绩事实的分页查询、详情查询、手工触发消费、汇总统计等接口。
 */
@Slf4j
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/perf/fact")
public class PerformanceFactController extends BaseController {

    private final IPerformanceQueryService queryService;
    private final PerformanceFactVoidService voidService;

    /**
     * 分页查询业绩事实列表。
     */
    @SaCheckPermission("perf:fact:list")
    @GetMapping("/list")
    public R<PageResult<PerformanceFactVo>> list(PerformanceFactBo query, PageQuery pageQuery) {
        return R.ok(queryService.listFacts(query, pageQuery));
    }

    /**
     * 业绩管理合同维度分页查询（合同 → 人 → 明细 懒加载树表）。
     */
    @SaCheckPermission("perf:fact:list")
    @GetMapping("/manage/contract")
    public R<PerformanceManagePageVo<PerformanceManageContractVo>> manageContract(
            PerformanceManageContractBo query, PageQuery pageQuery) {
        return R.ok(queryService.pageManageByContract(query, pageQuery));
    }

    /**
     * 按合同号集合查询业绩明细（合同维度树表懒加载）。
     */
    @SaCheckPermission("perf:fact:list")
    @GetMapping("/manage/contract/details")
    public R<List<PerformanceManageVo>> manageContractDetails(PerformanceManageContractDetailBo query) {
        return R.ok(queryService.listManageDetailsByContractNos(query));
    }

    /**
     * 查询有业绩数据的期间列表（倒序），供业绩明细页默认选中最新期间。
     */
    @SaCheckPermission("perf:fact:list")
    @GetMapping("/manage/periods")
    public R<List<String>> managePeriods() {
        return R.ok(queryService.listManagePeriods());
    }

    /**
     * 合同级作废：该合同该期间全部有效业绩一次性作废。
     */
    @SaCheckPermission("perf:fact:void")
    @Log(title = "业绩合同级作废", businessType = BusinessType.UPDATE)
    @PostMapping("/void-contract")
    public R<Integer> voidByContract(@Validated ContractVoidBo query) {
        return R.ok(voidService.voidByContract(query));
    }

    /**
     * 合同级恢复：该合同该期间全部已作废业绩一次性恢复。
     */
    @SaCheckPermission("perf:fact:void")
    @Log(title = "业绩合同级恢复", businessType = BusinessType.UPDATE)
    @PostMapping("/restore-contract")
    public R<Integer> restoreByContract(@Validated ContractVoidBo query) {
        return R.ok(voidService.restoreByContract(query));
    }

    /**
     * 完整业绩查询（合同维度）。
     */
    @SaCheckPermission("perf:fact:list")
    @GetMapping("/search")
    public R<PageResult<PerformanceFactSearchVo>> search(PerformanceSearchBo query, PageQuery pageQuery) {
        return R.ok(queryService.searchByContract(query, pageQuery));
    }

    /**
     * 完整业绩查询的业务类型下拉选项。
     */
    @SaCheckPermission("perf:fact:list")
    @GetMapping("/search/biz-types")
    public R<List<String>> searchBizTypes(PerformanceSearchBizTypesBo query) {
        return R.ok(queryService.searchBizTypes(query));
    }

    /**
     * 完整业绩查询·员工下拉选项（按姓名/工号远程搜索）。
     */
    @SaCheckPermission("perf:fact:list")
    @GetMapping("/search/employee-options")
    public R<List<EmployeeMainDataDTO>> searchEmployeeOptions(@Validated PerformanceSearchEmployeeOptionsBo query) {
        return R.ok(queryService.searchEmployeeOptions(query));
    }

    /**
     * 完整业绩查询·按业务键查询合同下明细（查看详情弹窗）。
     */
    @SaCheckPermission("perf:fact:list")
    @GetMapping("/search/details")
    public R<List<PerformanceSearchDetailVo>> searchDetails(@Validated PerformanceSearchDetailBo query) {
        return R.ok(queryService.searchDetails(query.getBizNo()));
    }
}
