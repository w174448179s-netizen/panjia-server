package com.panjia.contracts.port;

import com.panjia.contracts.dto.HistoryRealFactDTO;
import com.panjia.contracts.dto.PerformanceContractSummaryDTO;
import com.panjia.contracts.dto.PerformanceFactSummaryDTO;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 业绩事实跨域查询端口（performance 域对外契约，对结佣域唯一出口）。
 * <p>
 * 定义在 panjia-contracts 叶子模块，实现方为 panjia-performance，
 * 结佣域（panjia-commission）只依赖本端口，<b>严禁直连 {@code pj_perf_*} 表</b>（CI C3/C4）。
 * <p>
 * 口径约定：
 * <ul>
 *   <li>所有查询只返回事实快照字段，金额为业绩域原样值，消费方不得二次折算；</li>
 *   <li>{@code findActiveByDept} / {@code findActiveByEmployee} / {@code findActiveByFacts}
 *       只返回 {@code ACTIVE} 状态事实（含 amount = 0 的行，0 值过滤由结佣域自行处理）；</li>
 *   <li>{@code getByFactId} 供溯源链路使用，任意状态事实均可查（含 REVERSED）。</li>
 * </ul>
 */
public interface CommissionPerformanceQueryPort {

    /**
     * 按期间 + 门店查 ACTIVE 业绩事实。
     *
     * @param period   归属期间 YYYY-MM
     * @param deptId   门店 ID
     * @param factType 事实口径（FactType code：PERF_REAL / PERF_EXPECT）
     * @return 事实摘要列表（含 amount = 0 的行，过滤留给消费方）
     */
    List<PerformanceFactSummaryDTO> findActiveByDept(String period, Long deptId, String factType);

    /**
     * 按期间 + 员工查 ACTIVE 业绩事实。
     *
     * @param period     归属期间 YYYY-MM
     * @param employeeId 员工 ID
     * @param factType   事实口径（FactType code）
     * @return 事实摘要列表
     */
    List<PerformanceFactSummaryDTO> findActiveByEmployee(String period, Long employeeId, String factType);

    /**
     * 按员工 + 期间区间逐月汇总 ACTIVE 业绩事实原始金额
     * （pj_perf_fact.performance_amount 原样求和，不折算、不按 factType 过滤）。
     * <p>薪酬域职级晋升判定用：累计业绩 = [fromPeriod, toPeriod]（含两端，YYYY-MM
     * 字符串区间）内 ACTIVE 事实按 (employeeId, period) 分组合计，调用方按各自
     * 职级生效月截取累加。
     *
     * @param employeeIds 员工 ID 集合（不可为空）
     * @param fromPeriod  起始期间 YYYY-MM（含）
     * @param toPeriod    截止期间 YYYY-MM（含）
     * @return "employeeId|period" → 该员工该月合计；无事实的键不在结果中
     */
    Map<String, BigDecimal> sumActiveByEmployeePeriods(Collection<Long> employeeIds, String fromPeriod, String toPeriod);

    /**
     * 按事实 ID 集合查 ACTIVE 业绩事实（冲销联动 / 批量溯源用）。
     *
     * @param factIds 事实 ID 集合
     * @return 事实摘要列表（仅 ACTIVE；不存在的 ID 不在结果中）
     */
    List<PerformanceFactSummaryDTO> findActiveByFacts(Collection<Long> factIds);

    /**
     * 按事实 ID 查单条事实（不限状态，溯源链路用；含 REVERSED）。
     *
     * @param factId 事实 ID
     * @return 事实摘要；不存在返回 null
     */
    PerformanceFactSummaryDTO getByFactId(Long factId);

    /**
     * 按期间 + 合同号查 ACTIVE 业绩事实（结佣按合同发起用）。
     *
     * @param period     归属期间 YYYY-MM
     * @param contractNo 合同号
     * @param factType   事实口径（FactType code：PERF_REAL / PERF_EXPECT）
     * @return 事实摘要列表（含合同号/订单号/房源地址；含 amount = 0 的行，过滤留给消费方）
     */
    List<PerformanceFactSummaryDTO> findActiveByContract(String period, String contractNo, String factType);

    /**
     * 按期间 + 订单号/合同号查 ACTIVE 业绩事实（<b>订单号优先</b>，调整链路专用）。
     * <p>
     * 同一合同号可能挂多个订单号，按合同号双键装载会跨订单混排明细；本方法
     * 订单号非空时按 {@code order_no} 精确匹配，未命中（数据修正/历史脏数据）回退
     * 合同号双键口径，订单号为空直接按合同号。
     *
     * @param period     归属期间 YYYY-MM
     * @param orderNo    订单号（可空）
     * @param contractNo 合同号
     * @param factType   事实口径（FactType code：PERF_REAL / PERF_EXPECT）
     * @return 事实摘要列表
     */
    List<PerformanceFactSummaryDTO> findActiveByBizKey(String period, String orderNo, String contractNo, String factType);

    /**
     * 按业务键集合（订单号/合同号）查 ACTIVE 业绩事实（<b>不限归属期间，跨月</b>）。
     * <p>
     * 用途：新签可能早于到账月（如 7 月新签、8 月到账），结佣明细金额须取该人该合同
     * 的新签金额（跨月查找）；order_no 或 contract_no 命中键集合即返回。
     *
     * @param bizKeys  订单号或合同号集合（不可为空）
     * @param factType 事实口径（FactType code：PERF_REAL / PERF_EXPECT）
     * @return 事实摘要列表（含 amount = 0 的行，过滤留给消费方）
     */
    List<PerformanceFactSummaryDTO> findActiveByBizKeys(Collection<String> bizKeys, String factType);

    /**
     * 按期间查「合同」维度业绩汇总（结佣申请列表与合同申请单合并展示用）。
     *
     * @param period   归属期间 YYYY-MM；可空，空时跨全部期间汇总（结佣列表仅录合同号场景）
     * @param deptId   门店 ID（null 查全部；非 null 含下级部门，与业绩明细页口径一致）
     * @param factType 事实口径（FactType code）
     * @return 合同维度摘要列表（仅 contract_no 非空的合同，按签约时间倒序由调用方排序）
     */
    List<PerformanceContractSummaryDTO> listContractSummaries(String period, Long deptId, String factType, Long employeeId, String keyword);

    /**
     * 按期间 + 业务键集合（合同号/订单号）统计跨合同去重员工数（结佣明细列表合计用）。
     *
     * @param period   归属期间 YYYY-MM；可空，空时统计全部期间
     * @param bizKeys  合同号/订单号业务键集合（不可为空）
     * @param factType 事实口径（FactType code：PERF_REAL 走实收拆表，其余走 pj_perf_fact）
     * @return 去重员工数；无匹配返回 0
     */
    long countDistinctEmployeesByKeys(String period, Collection<String> bizKeys, String factType);

    /**
     * 结佣发起页主查询：实收<b>审批通过</b>的审批单行（订单维度，一行=一张审批单）。
     * <p>
     * 展示字段取审批单快照（合同号/订单号/业务类型/房源地址/签约日期/实收金额），
     * 员工数与明细数经实收明细按 received_apply_id 聚合现算；
     * 门店（含下级）与员工过滤经实收明细判定（审批单 dept_id 快照为空）；
     * keyword 命中合同号/订单号/房源地址，bizType 精确匹配，均下推 SQL。
     * 排除已结佣与结佣金额（订单号→合同号退化）由结佣域处理。
     *
     * @param deptId     门店 ID（null 查全部；非 null 含下级部门）
     * @param employeeId 员工 ID（null 不过滤；经实收明细 COALESCE(员工 ID, 工号兜底) 匹配）
     * @param keyword    关键字（可空；合同号/订单号/房源地址 LIKE）
     * @param bizType    业务类型（可空；精确匹配）
     * @return 审批单行（按签约日期倒序，id 次序兜底）；无数据返回空列表
     */
    List<PerformanceContractSummaryDTO> listApprovedApplies(Long deptId, Long employeeId, String keyword, String bizType);

    /**
     * 批量查合同维度「调整前」事实金额合计（结佣明细列表展示「原值 → 调整后值」用）。
     * <p>
     * 口径：以各合同当前 ACTIVE 事实的 sourceKey 集合为准，沿事实链（同 sourceKey，
     * 含历史 REVERSED 事实）取最早一条事实金额求和；从未调整的合同其原值=当前合计。
     * 已 VOID 冲销（无 ACTIVE 事实）的行不计入原值，与当前列表口径一致。
     *
     * @param period   归属期间 YYYY-MM
     * @param bizKeys  合同号/订单号业务键集合（不可为空）
     * @param factType 事实口径（FactType code：PERF_REAL / PERF_EXPECT）
     * @return bizKey → 调整前合计；无 ACTIVE 事实的键不在结果中
     */
    Map<String, BigDecimal> sumOriginalAmountsByKeys(String period, Collection<String> bizKeys, String factType);

    /**
     * 判断业务键 ACTIVE 事实的 source_key 链是否存在 REVERSED 行（「是否已调整」判定用，不限期间）。
     * <p>
     * 口径：以各业务键当前 ACTIVE 事实的 source_key 集合为准，任一链上存在 REVERSED 行即返回 true。
     * PERF_REAL 委托实收域（{@code pj_received_detail}），其余口径查 {@code pj_perf_fact}。
     * 2026-10-15 结佣发起闸门用：新签（PERF_EXPECT）已调整而实收（PERF_REAL）未调整时拦截发起。
     *
     * @param bizKeys  合同号/订单号业务键集合（空集合返回 false，不发起 SQL）
     * @param factType 事实口径（FactType code：PERF_REAL / PERF_EXPECT）
     * @return true = 已调整（存在 REVERSED 链）；false = 从未调整
     */
    boolean hasReversedChain(Collection<String> bizKeys, String factType);

    /**
     * 判断业务键下是否存在<b>进行中</b>的新签业绩调整单（SUBMITTED/APPROVED，未执行）。
     * <p>
     * 2026-10-17 结佣发起闸门补强：在途新签调整尚未 supersede 事实（无 REVERSED 链），
     * {@link #hasReversedChain} 判不出来；但调整后金额会变，此时放行结佣会按旧快照结错。
     * 有在途新签调整单 → 拦截发起，提示先完成调整审批。
     *
     * @param bizKeys 合同号/订单号业务键集合（空集合返回 false，不发起 SQL）
     * @return true = 存在审批中/已通过未执行的新签调整单
     */
    boolean hasInflightExpectAdjust(Collection<String> bizKeys);

    /**
     * 明细级金额调整：单条事实 supersede 为 targetAmount（实收调整执行用）。
     *
     * @param factId       事实 ID
     * @param targetAmount 调整后金额
     * @param operatorId   操作人 ID
     * @param adjustId     调整单 ID
     * @return 新事实 ID
     */
    Long adjustFactAmount(Long factId, BigDecimal targetAmount, Long operatorId, Long adjustId);

    /**
     * 历史工资导入批次实收事实明细（历史 LOCKED 结佣建单用）。
     * <p>
     * 返回指定批次下 factType=PERF_REAL 且 ACTIVE 的事实行（含订单号/合同号/
     * 房源地址/费用项等建单字段）。是否已绑定结佣明细的过滤由结佣域自行处理
     * （查自身 pj_commission_item.performance_fact_id）。
     *
     * @param period  归属期间 YYYY-MM
     * @param batchId 导入批次 ID
     * @return 实收事实明细（按事实 ID 升序）；无数据返回空列表
     */
    List<HistoryRealFactDTO> listRealFactsByBatch(String period, Long batchId);

    /**
     * 按业务键汇总期间应收事实金额（历史 LOCKED 建单 expected_amount 用）。
     * <p>
     * 口径：factType=PERF_EXPECT 且 ACTIVE，业务键 = 订单号优先，空回退合同号，
     * 再回退 sourceKey（与事实生成侧 buildSourceKeyPrefix 的业务键约定一致）。
     *
     * @param period  归属期间 YYYY-MM
     * @param bizKeys 业务键集合（不可为空）
     * @return bizKey → 应收合计；无事实的键不在结果中
     */
    Map<String, BigDecimal> sumExpectAmountsByKeys(String period, Collection<String> bizKeys);

    /**
     * 按业务键集合（订单号/合同号）<b>跨期间</b>汇总 ACTIVE PERF_EXPECT 应收金额。
     * <p>
     * 用途：结佣申请单 expectedAmount 与跨月到账判定同口径（该订单/合同全部月份新签合计）。
     * 同一事实同时计入其订单号键与合同号键（均非空时），调用方按输入键取值。
     *
     * @param bizKeys 订单号或合同号集合（不可为空）
     * @return bizKey → 应收合计；无事实的键不在结果中
     */
    Map<String, BigDecimal> sumExpectAmountsByKeysCrossPeriod(Collection<String> bizKeys);

    /**
     * 按业务键集合（订单号/合同号）<b>跨期间</b>汇总 ACTIVE PERF_EXPECT 应收金额，按员工过滤。
     * <p>
     * 用途：结佣发起页按员工筛选时，结佣业绩列展示该员工在新签事实中的业绩合计，而非合同全量。
     * 同一事实同时计入其订单号键与合同号键（均非空时），调用方按输入键取值。
     *
     * @param bizKeys    订单号或合同号集合（不可为空）
     * @param employeeId 员工 ID（不可为空；为空时请用 {@link #sumExpectAmountsByKeysCrossPeriod}）
     * @return bizKey → 该员工应收合计；无事实的键不在结果中
     */
    Map<String, BigDecimal> sumExpectAmountsByKeysCrossPeriodForEmployee(Collection<String> bizKeys, Long employeeId);

    /**
     * 解析实收事实对应的<b>新签导入月</b>（薪酬域结佣提成规则期间用，2026-10-17 改）。
     * <p>
     * 口径：历史导入/历史单的结佣明细绑定实收明细（PERF_REAL），其 period 是结佣导入月
     * 而非新签月；本方法按「订单号+员工 → 合同号+员工（订单号未命中时退化）」匹配
     * ACTIVE 新签事实（PERF_EXPECT），取<b>最早归属月（MIN period）</b>作为该笔结佣的
     * 新签导入月（同合同跨月补差/分期的多期新签行，新签月以最初导入月为准）。
     *
     * @param realFacts 实收事实摘要集合（需含 factId/orderNo/contractNo/employeeId）
     * @return realFactId → 新签导入月（YYYY-MM）；未匹配到新签事实的 realFactId 不在结果中
     */
    Map<Long, String> findNewSignPeriodsByRealFacts(Collection<PerformanceFactSummaryDTO> realFacts);
}
