package com.panjia.performance.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.panjia.contracts.dto.EmployeeMainDataDTO;
import com.panjia.performance.dto.FactQuery;
import com.panjia.performance.dto.PerformanceFactDTO;
import com.panjia.performance.dto.PerformanceFactSearchDTO;
import com.panjia.performance.dto.PerformanceManageContractVO;
import com.panjia.performance.dto.PerformanceManageDTO;
import com.panjia.performance.dto.PerformanceManagePageVO;
import com.panjia.performance.dto.PerformanceSearchDetailDTO;
import com.panjia.performance.service.PerformanceFactVoidService;
import com.panjia.performance.service.PerformanceQueryService;
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
import org.springframework.web.bind.annotation.RequestParam;
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

    private final PerformanceQueryService queryService;
    private final PerformanceFactVoidService voidService;

    /**
     * 分页查询业绩事实列表。
     *
     * @param query     筛选条件
     * @param pageQuery 分页参数
     * @return 业绩事实分页
     */
    @SaCheckPermission("perf:fact:list")
    @GetMapping("/list")
    public R<PageResult<PerformanceFactDTO>> list(FactQuery query, PageQuery pageQuery) {
        return R.ok(queryService.listFacts(query, pageQuery));
    }

    /**
     * 业绩管理合同维度分页查询（合同 → 人 → 明细 懒加载树表）。
     * <p>
     * 以合同号为分页维度：只返回当前页合同的聚合行（合同号/订单号/类型/房源地址/
     * 签约日期/合同金额/涉及人数/明细数）、业务类型选项与跨页全局汇总；
     * 合同下签约人明细由 {@link #manageContractDetails} 懒加载。
     */
    @SaCheckPermission("perf:fact:list")
    @GetMapping("/manage/contract")
    public R<PerformanceManagePageVO<PerformanceManageContractVO>> manageContract(
            @RequestParam String period,
            @RequestParam String factType,
            @RequestParam(required = false) Long deptId,
            @RequestParam(required = false) Long employeeId,
            @RequestParam(required = false) String bizType,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String factStatus,
            @RequestParam(required = false, defaultValue = "1") Integer pageNum,
            @RequestParam(required = false, defaultValue = "20") Integer pageSize) {
        return R.ok(queryService.pageManageByContract(period, factType, deptId, employeeId, bizType,
            keyword, factStatus, pageNum, pageSize));
    }

    /**
     * 按合同号集合查询业绩明细（合同维度树表懒加载）。
     * <p>
     * 展开单个合同时 contractNos 传 1 个；「全部展开」时传当前页全部合同号。
     * 其余过滤条件与 {@link #manageContract} 一致。
     */
    @SaCheckPermission("perf:fact:list")
    @GetMapping("/manage/contract/details")
    public R<List<PerformanceManageDTO>> manageContractDetails(
            @RequestParam List<String> contractNos,
            @RequestParam String period,
            @RequestParam String factType,
            @RequestParam(required = false) Long deptId,
            @RequestParam(required = false) String bizType,
            @RequestParam(required = false) Boolean settled,
            @RequestParam(required = false) String keyword) {
        return R.ok(queryService.listManageDetailsByContractNos(period, factType, deptId, bizType, settled,
            keyword, contractNos));
    }

    /**
     * 查询有业绩数据的期间列表（倒序），供业绩明细页默认选中最新期间。
     *
     * @return 期间列表（YYYY-MM）
     */
    @SaCheckPermission("perf:fact:list")
    @GetMapping("/manage/periods")
    public R<java.util.List<String>> managePeriods() {
        return R.ok(queryService.listManagePeriods());
    }

    /**
     * 合同级作废：该合同该期间全部有效业绩一次性作废（不区分人员/角色），
     * 作废后整张合同不参与算薪/结佣，可按合同整体恢复。
     *
     * @param period     归属期间
     * @param factType   事实口径（新签明细 PERF_EXPECT）
     * @param contractNo 合同号（或订单号）
     * @param reason     作废原因
     * @return 作废明细条数
     */
    @SaCheckPermission("perf:fact:void")
    @Log(title = "业绩合同级作废", businessType = BusinessType.UPDATE)
    @PostMapping("/void-contract")
    public R<Integer> voidByContract(@RequestParam String period, @RequestParam String factType,
                                     @RequestParam String contractNo, @RequestParam String reason) {
        return R.ok(voidService.voidByContract(period, factType, contractNo, reason));
    }

    /**
     * 合同级恢复：该合同该期间全部已作废业绩一次性恢复，period 改为当前月。
     *
     * @param period     原归属期间
     * @param factType   事实口径
     * @param contractNo 合同号（或订单号）
     * @param reason     恢复原因
     * @return 恢复明细条数
     */
    @SaCheckPermission("perf:fact:void")
    @Log(title = "业绩合同级恢复", businessType = BusinessType.UPDATE)
    @PostMapping("/restore-contract")
    public R<Integer> restoreByContract(@RequestParam String period, @RequestParam String factType,
                                        @RequestParam String contractNo, @RequestParam String reason) {
        return R.ok(voidService.restoreByContract(period, factType, contractNo, reason));
    }

    /**
     * 完整业绩查询（合同维度）。
     * <p>
     * 以合同为维度，展示新签业绩、实收业绩、调整状态与金额、实收审批状态、结佣状态。
     * 支持按期间、部门、业务类型、关键字、员工筛选；指定 employeeId 时金额仅汇总该员工个人份额。
     */
    @SaCheckPermission("perf:fact:list")
    @GetMapping("/search")
    public R<PageResult<PerformanceFactSearchDTO>> search(
            @RequestParam(required = false) String period,
            @RequestParam(required = false) Long deptId,
            @RequestParam(required = false) String bizType,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Long employeeId,
            @RequestParam(defaultValue = "1") Integer pageNum,
            @RequestParam(defaultValue = "20") Integer pageSize) {
        return R.ok(queryService.searchByContract(period, deptId, bizType, keyword,
            employeeId, pageNum, pageSize));
    }

    /**
     * 完整业绩查询的业务类型下拉选项（数据范围与 /search 一致：期间/部门子树/经纪人本人/选中员工）。
     */
    @SaCheckPermission("perf:fact:list")
    @GetMapping("/search/biz-types")
    public R<List<String>> searchBizTypes(
            @RequestParam(required = false) String period,
            @RequestParam(required = false) Long deptId,
            @RequestParam(required = false) Long employeeId) {
        return R.ok(queryService.searchBizTypes(period, deptId, employeeId));
    }

    /**
     * 完整业绩查询·员工下拉选项（按姓名/工号远程搜索）。
     * <p>
     * 仅返回当前登录用户部门数据权限范围内的员工（经纪人只返回本人），
     * 供业绩查询页员工选择框使用，防止通过员工选择越权查询他部门员工业绩。
     *
     * @param keyword 姓名或工号关键字（必填）
     * @param deptId  部门 ID（可选，含子部门）
     * @return 员工选项（含工号/姓名/部门全路径名，最多 20 条）
     */
    @SaCheckPermission("perf:fact:list")
    @GetMapping("/search/employee-options")
    public R<List<EmployeeMainDataDTO>> searchEmployeeOptions(
            @RequestParam String keyword,
            @RequestParam(required = false) Long deptId) {
        return R.ok(queryService.searchEmployeeOptions(keyword, deptId));
    }

    /**
     * 完整业绩查询·按业务键查询合同下明细（查看详情弹窗）。
     * <p>
     * 业务键口径：一手房、房产金融、家装荐客传订单号，其余传合同号（合同号为空回退订单号），
     * 即列表行展示的合同号/订单号。返回该业务键全部期间的明细行（应收/实收双口径）。
     *
     * @param bizNo 业务键（合同号或订单号）
     * @return 合同下明细行
     */
    @SaCheckPermission("perf:fact:list")
    @GetMapping("/search/details")
    public R<List<PerformanceSearchDetailDTO>> searchDetails(@RequestParam String bizNo) {
        return R.ok(queryService.searchDetails(bizNo));
    }
}
