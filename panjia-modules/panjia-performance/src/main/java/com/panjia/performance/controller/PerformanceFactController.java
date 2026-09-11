package com.panjia.performance.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.panjia.performance.dto.FactQuery;
import com.panjia.performance.dto.PerformanceFactDTO;
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
        performanceEngine.buildFromBatch(batchId, eventId, "MANUAL_BUILD",
            LoginHelper.getUserId(), Collections.emptyList());
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
}
