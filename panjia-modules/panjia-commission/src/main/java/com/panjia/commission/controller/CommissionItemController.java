package com.panjia.commission.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.panjia.commission.domain.CommissionConsumeLog;
import com.panjia.commission.domain.CommissionItem;
import com.panjia.commission.dto.ConsumeLogQuery;
import com.panjia.commission.dto.ItemQuery;
import com.panjia.commission.dto.ItemTraceVO;
import com.panjia.commission.service.CommissionApplicationService;
import com.panjia.contracts.dto.PerformanceFactSummaryDTO;
import com.panjia.contracts.port.CommissionPerformanceQueryPort;
import com.panjia.contracts.port.ImportNormalizedRecordQueryPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.domain.PageResult;
import org.dromara.common.core.domain.R;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.web.core.BaseController;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 结佣明细 / 新签透传 / 溯源 / 消费日志查询。
 */
@Slf4j
@RequiredArgsConstructor
@RestController
public class CommissionItemController extends BaseController {

    private final CommissionApplicationService applicationService;
    private final CommissionPerformanceQueryPort performanceQueryPort;
    private final ImportNormalizedRecordQueryPort importQueryPort;

    /**
     * 结佣明细分页查询。
     *
     * @param query     筛选条件
     * @param pageQuery 分页参数
     * @return 明细分页
     */
    @SaCheckPermission("commission:item:list")
    @GetMapping("/commission/item/list")
    public R<PageResult<CommissionItem>> listItems(ItemQuery query, PageQuery pageQuery) {
        return R.ok(applicationService.listItems(query, pageQuery));
    }

    /**
     * 新签业绩透传查询（PERF_EXPECT，业绩域原样值，不折算；供店长/总监提成基数查看）。
     *
     * @param period 业绩归属月（YYYY-MM）
     * @param deptId 门店 ID
     * @return 新签业绩列表
     */
    @SaCheckPermission("commission:newsign:list")
    @GetMapping("/commission/new-sign")
    public R<List<PerformanceFactSummaryDTO>> listNewSign(@RequestParam String period, @RequestParam Long deptId) {
        return R.ok(performanceQueryPort.findActiveByDept(period, deptId, "PERF_EXPECT"));
    }

    /**
     * 工资-结佣溯源（结佣明细 → 业绩事实 → 贝壳原始行）。
     * <p>
     * 穿透到导入原始行 raw_json（含文件名 / Sheet / 行号等全 30 列）；DIFF 差额行无业绩事实，
     * fact / rawJson 为 null。
     *
     * @param itemId 结佣明细 ID
     * @return 溯源 VO
     */
    @SaCheckPermission("commission:trace:query")
    @GetMapping("/commission/trace/{itemId}")
    public R<ItemTraceVO> trace(@PathVariable Long itemId) {
        List<CommissionItem> items = applicationService.listItemsByItemId(itemId);
        if (items.isEmpty()) {
            return R.fail("结佣明细不存在：" + itemId);
        }
        ItemTraceVO vo = new ItemTraceVO();
        CommissionItem item = items.get(0);
        vo.setItem(item);
        if (item.getPerformanceFactId() != null) {
            PerformanceFactSummaryDTO fact = performanceQueryPort.getByFactId(item.getPerformanceFactId());
            vo.setFact(fact);
            if (fact != null && fact.getNormalizedRecordId() != null) {
                vo.setRawJson(importQueryPort.getRawJsonByRecordId(fact.getNormalizedRecordId()));
            }
        }
        return R.ok(vo);
    }

    /**
     * 消费日志分页查询（幂等状态、冲销影响留痕）。
     *
     * @param query     筛选条件
     * @param pageQuery 分页参数
     * @return 消费日志分页
     */
    @SaCheckPermission("commission:consumelog:list")
    @GetMapping("/commission/consume-log/list")
    public R<PageResult<CommissionConsumeLog>> listConsumeLogs(ConsumeLogQuery query, PageQuery pageQuery) {
        return R.ok(applicationService.listConsumeLogs(query, pageQuery));
    }
}
