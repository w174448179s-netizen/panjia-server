package com.panjia.performance.service;

import com.panjia.performance.domain.PerfRefund;
import com.panjia.performance.domain.bo.PerfRefundBo;
import com.panjia.performance.domain.bo.PerfRefundCreateBo;
import org.dromara.common.core.domain.PageResult;
import org.dromara.common.mybatis.core.page.PageQuery;

import java.util.List;
import java.util.Map;

/**
 * 退单服务（pj_perf_refund）。
 * <p>
 * 退单 ≠ 手工冲正：退单以合同为单位全额冲退，审批通过后同时生成
 * 负新签（PERF_EXPECT）与负实收（PERF_REAL）流水，单据本身即「合同已退单」状态源。
 * 已封账（已发薪）月份禁止退单；退单不可撤销，录错只能另发业绩调整单补正。
 */
public interface IPerfRefundService {

    /** 分页查询退单列表 */
    PageResult<PerfRefund> list(PerfRefundBo query, PageQuery pageQuery);

    /** 按 ID 查退单详情（审批弹窗用，含部门名称回填） */
    PerfRefund getById(Long id);

    /** 退单汇总（列表页顶部：冲减新签/冲减实收/笔数） */
    Map<String, Object> summary(String refundPeriod);

    /** 批量查合同最近退单落账月（合同列表「已退单」标记） */
    Map<String, String> refundedPeriods(List<String> contractNos);

    /** 退单预览：返回将冲减的新签/实收金额与明细笔数，并给出拦截原因 */
    Map<String, Object> preview(String contractNo, String orderNo, String refundPeriod);

    /** 发起退单并启动审批 */
    PerfRefund create(PerfRefundCreateBo dto, Long applicantId);

    /** 申请人撤回审批中的退单（仅 SUBMITTED、仅发起人） */
    void withdraw(Long id, Long userId);

    /** 工作流回调（finish → 审批通过并执行；back/invalid → REJECTED） */
    void handleWorkflowEvent(Long id, String status, String handler, String message);

    /** 认领当前节点待办（事后补赋角色） */
    boolean claim(Long id);

    /**
     * 贝壳导入退单自动登记（2026-10-25 定稿）：经纪人明细表导入的负数新签行
     * （REVERSAL_TYPE=REDINK_REFUND、SOURCE=IMPORT）按「合同+订单+退单月」分组，
     * 直接生成 <b>EXECUTED</b> 退单记录（无需人工审批），退单管理可查询。
     * <p><b>负结佣不在本方法产生</b>：负新签单独到位不冲减结佣，须待负实收也到位后
     * 由 {@link #syncImportedRefunds(String)} 统一生成（且前提是该合同确有已结佣记录）。
     * <p>幂等：同一批次同一合同同一退单月已登记时跳过（事件重试/批次重导安全）。
     *
     * @param batchId           新签导入批次 ID
     * @param period            批次归属月 YYYY-MM（负数事实缺归属月时兜底）
     * @param operatorId        操作人 ID
     * @param supersededBatchIds 被本次重导冲销的旧批次 ID（撤销其自动退单与负结佣，可空）
     * @return 新登记退单记录数
     */
    int createFromImportedBatch(Long batchId, String period, Long operatorId,
                                List<Long> supersededBatchIds);

    /**
     * 撤销某导入批次自动登记的退单（批次撤销场景）：
     * 作废其负结佣冲减单（{@code TD + 退单ID}）并将退单状态置为 CANCELLED。
     * <p>背景：批次撤销会硬删业绩事实，若退单残留，合同仍被标记「已退单」，
     * 退单管理出现幽灵记录、结佣发起被误拦截。
     * <p>幂等：重复调用安全（仅处理 EXECUTED 状态的退单）。
     *
     * @param batchId 被撤销的导入批次 ID
     * @return 撤销的退单笔数
     */
    int cancelImportedRefundsByBatch(Long batchId);

    /**
     * 导入退单联动同步（负实收批次归档后调用）：
     * <ol>
     *   <li>回填该退单月的「冲减实收」合计（负到账，新签/实收分属不同批次需双向对齐）；</li>
     *   <li>当负实收与负新签<b>都已到位</b>、且该合同存在已结佣记录（APPROVED 正结佣明细）
     *       时，按红冲比例生成退单月的负结佣冲减单（{@code TD + 退单ID}）。</li>
     * </ol>
     * 负实收未到、或无可冲减的已结佣记录时一律不产生负结佣。
     *
     * @param period 退单/实收归属月 YYYY-MM
     * @return 同步（回填实收或生成负结佣）的退单记录数
     */
    int syncImportedRefunds(String period);
}
