package com.panjia.performance.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.panjia.performance.dto.FactQuery;
import com.panjia.performance.dto.PerformanceFactDTO;
import com.panjia.performance.dto.PerformanceManageContractVO;
import com.panjia.performance.dto.PerformanceManageDTO;
import com.panjia.performance.dto.PerformanceManagePageVO;
import com.panjia.performance.service.PerformanceEngine;
import com.panjia.performance.service.PerformanceQueryService;
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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.Collections;
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
    private final PerformanceEngine performanceEngine;

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
     * 查询业绩事实详情。
     *
     * @param id 事实 ID
     * @return 业绩事实详情
     */
    @SaCheckPermission("perf:fact:query")
    @GetMapping("/{id}")
    public R<PerformanceFactDTO> getInfo(@PathVariable Long id) {
        return R.ok(queryService.getFact(id));
    }

    /**
     * 手工触发批次消费（重新生成业绩）。
     * <p>
     * 手工重跑语义：不携带 supersede 信息（supersede 链路由 import 域归档事件驱动），
     * 这里传空列表，跳过 §2.5 旧批次冲销。
     *
     * @param batchId 批次 ID
     * @return 操作结果
     */
    @SaCheckPermission("perf:fact:build")
    @Log(title = "业绩消费", businessType = BusinessType.OTHER)
    @PostMapping("/build/{batchId}")
    public R<Void> build(@PathVariable Long batchId) {
        String eventId = "MANUAL_BUILD_" + batchId + "_" + System.currentTimeMillis();
        // MANUAL_BUILD 无事件上下文，sourceType/period 传 null（RUNNING 日志允许空，消费完成后兜底推导）
        performanceEngine.buildFromBatch(batchId, eventId, "MANUAL_BUILD",
            LoginHelper.getUserId(), Collections.emptyList(), null, null);
        return R.ok();
    }

    /**
     * 业绩汇总。
     *
     * @param period     归属期间（可选）
     * @param factType   事实口径（可选）
     * @param employeeId 员工 ID（可选）
     * @param deptId     部门 ID（可选）
     * @return 业绩金额汇总
     */
    @SaCheckPermission("perf:fact:list")
    @GetMapping("/summary")
    public R<BigDecimal> summary(
            @RequestParam(required = false) String period,
            @RequestParam(required = false) String factType,
            @RequestParam(required = false) Long employeeId,
            @RequestParam(required = false) Long deptId) {
        return R.ok(queryService.sumPerformance(period, factType, employeeId, deptId));
    }

    /**
     * 业绩管理人维度分页查询（人 → 合同 → 明细 懒加载树表）。
     * <p>
     * 以签约人为分页维度：只返回当前页签约人的聚合行（员工号/姓名/金额合计/合同数/
     * 明细数）、业务类型选项与跨页全局汇总；人下明细由 {@link #manageDetails} 懒加载。
     *
     * @param period   归属期间（必填）
     * @param factType 事实口径：PERF_REAL（结佣业绩/实收）或 PERF_EXPECT（新签业绩/应收）
     * @param deptId   部门 ID（可选，含子部门）
     * @param bizType  业务类型（可选）
     * @param settled  是否已结算（可选）
     * @param keyword  关键字（可选：员工号/姓名/合同号/订单号/房源地址/角色/门店/店组）
     * @param pageNum  页码（默认 1）
     * @param pageSize 每页人数（默认 20，最大 200）
     * @return 人维度分页结果
     */
    @SaCheckPermission("perf:fact:list")
    @GetMapping("/manage")
    public R<PerformanceManagePageVO> manage(
            @RequestParam String period,
            @RequestParam String factType,
            @RequestParam(required = false) Long deptId,
            @RequestParam(required = false) String bizType,
            @RequestParam(required = false) Boolean settled,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false, defaultValue = "1") Integer pageNum,
            @RequestParam(required = false, defaultValue = "20") Integer pageSize) {
        return R.ok(queryService.pageManage(period, factType, deptId, bizType, settled,
            keyword, pageNum, pageSize));
    }

    /**
     * 按员工 ID 集合查询业绩明细（树表懒加载）。
     * <p>
     * 展开单个签约人时 employeeIds 传 1 个；「全部展开」时传当前页全部员工 ID。
     * 其余过滤条件与 {@link #manage} 一致。
     *
     * @param employeeIds 员工 ID 集合（逗号分隔，不能为空）
     * @param period      归属期间（必填）
     * @param factType    事实口径（必填）
     * @param deptId      部门 ID（可选，含子部门）
     * @param bizType     业务类型（可选）
     * @param settled     是否已结算（可选）
     * @param keyword     关键字（可选）
     * @return 业绩明细行
     */
    @SaCheckPermission("perf:fact:list")
    @GetMapping("/manage/details")
    public R<List<PerformanceManageDTO>> manageDetails(
            @RequestParam List<Long> employeeIds,
            @RequestParam String period,
            @RequestParam String factType,
            @RequestParam(required = false) Long deptId,
            @RequestParam(required = false) String bizType,
            @RequestParam(required = false) Boolean settled,
            @RequestParam(required = false) String keyword) {
        return R.ok(queryService.listManageDetails(period, factType, deptId, bizType, settled,
            keyword, employeeIds));
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
            @RequestParam(required = false) String bizType,
            @RequestParam(required = false) Boolean settled,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false, defaultValue = "1") Integer pageNum,
            @RequestParam(required = false, defaultValue = "20") Integer pageSize) {
        return R.ok(queryService.pageManageByContract(period, factType, deptId, bizType, settled,
            keyword, pageNum, pageSize));
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
}
