package com.panjia.contracts.port;

import com.panjia.contracts.dto.CommissionAdjustMirrorDTO;
import com.panjia.contracts.dto.HistoryRealFactDTO;
import com.panjia.contracts.dto.PerformanceContractSummaryDTO;
import com.panjia.contracts.dto.PerformanceFactSummaryDTO;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
     * 按业务键集合（订单号/合同号）<b>跨期间</b>批量查 ACTIVE 事实的归属期间集合。
     * <p>用途：结佣发起页同期互斥排除——判断同一合同各实收期间是否均已有活跃结佣单。
     * 同一事实同时计入其订单号键与合同号键（均非空时），调用方按输入键取值。
     *
     * @param bizKeys  订单号或合同号集合（不可为空）
     * @param factType 事实口径（FactType code：PERF_REAL 走实收拆表，其余走 pj_perf_fact）
     * @return bizKey → 该合同全部 ACTIVE 事实的期间集合；无事实的键不在结果中
     */
    Map<String, Set<String>> listActivePeriodsByKeys(Collection<String> bizKeys, String factType);

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
     * 合同级金额调整：按合同下指定口径各 ACTIVE 事实当前金额占比分摊
     * （targetAmount − 当前合计）差额，逐条 supersede 为新金额（结佣调整用）。
     * <p>
     * 分摊尾差补到业绩金额绝对值最大的一条，保证 Σ新金额 = targetAmount 精确成立。
     * 与 {@code PerformanceAdjustServiceImpl.executeContractAmountAdjust} 同口径。
     *
     * @param period       归属期间
     * @param orderNo      订单号（可空；非空时订单号优先精确匹配）
     * @param contractNo   合同号
     * @param factType     事实口径（PERF_REAL / PERF_EXPECT）
     * @param targetAmount 调整后合计
     * @param operatorId   操作人 ID
     * @param adjustId     调整单 ID（写入新事实 adjust_id 与冲销链）
     * @return 旧事实 ID → 新事实 ID 映射（供结佣域回写 CommissionItem.performance_fact_id）
     */
    Map<Long, Long> adjustContractFactsAmount(String period, String orderNo, String contractNo, String factType,
                                              BigDecimal targetAmount, Long operatorId, Long adjustId);

    /**
     * 明细级金额调整：单条事实 supersede 为 targetAmount（结佣调整用）。
     *
     * @param factId       事实 ID
     * @param targetAmount 调整后金额
     * @param operatorId   操作人 ID
     * @param adjustId     调整单 ID
     * @return 新事实 ID
     */
    Long adjustFactAmount(Long factId, BigDecimal targetAmount, Long operatorId, Long adjustId);

    /**
     * 明细级金额+角色占比调整：单条事实 supersede 为 targetAmount，
     * shareRatio 非空时同步更新角色占比（结佣合同级指定值模式用，仅 PERF_EXPECT 路径）。
     *
     * @param factId       事实 ID
     * @param targetAmount 调整后金额
     * @param shareRatio   调整后角色占比（null=不变）
     * @param operatorId   操作人 ID
     * @param adjustId     调整单 ID
     * @return 新事实 ID
     */
    Long adjustFactAmount(Long factId, BigDecimal targetAmount, BigDecimal shareRatio, Long operatorId, Long adjustId);

    /**
     * 新增角色人事实（结佣调整 ADD_MEMBER 用，仅 PERF_EXPECT 路径）：
     * 以模板事实为基准复制合同/期间/业务类型等基础字段，覆盖员工/角色/金额，
     * 来源=MANUAL，批次/归一化记录引用置空。
     *
     * @param templateFactId 模板事实 ID（合同既有任一 ACTIVE 新签事实）
     * @param employeeId     新员工 ID
     * @param employeeCode   新员工工号
     * @param deptId         新部门 ID（null=跟随模板）
     * @param roleType       角色类型
     * @param amount         业绩金额
     * @param shareRatio     角色占比（可空）
     * @param operatorId     操作人 ID
     * @param adjustId       调整单 ID（写入新事实 adjust_id 与 sourceKey 幂等键）
     * @return 新事实 ID
     */
    Long createMemberFact(Long templateFactId, Long employeeId, String employeeCode, Long deptId,
                          String roleType, BigDecimal amount, BigDecimal shareRatio, Long operatorId, Long adjustId);

    /**
     * 结佣调整执行后登记新签调整单镜像（pj_perf_adjust，status=EXECUTED，无审批流）。
     * <p>
     * 新签界面「原值 → 调整后值」展示依赖 pj_perf_adjust 快照还原；结佣调整直接 supersede
     * 事实不产生新签调整单，执行时须同步登记镜像，否则新签侧只显示最终金额。
     * 同事务：镜像写入失败则整个执行回滚。仅 AMOUNT / ADD_MEMBER 调用。
     *
     * @param mirror 镜像单据内容（adjustNo 取结佣调整单号，唯一幂等）
     */
    void recordExecutedAdjustMirror(CommissionAdjustMirrorDTO mirror);

    /**
     * 明细级业绩冲销：单条事实冲销（结佣调整 VOID 用）。
     *
     * @param factId     事实 ID
     * @param operatorId 操作人 ID
     * @param adjustId   调整单 ID
     */
    void voidFact(Long factId, Long operatorId, Long adjustId);

    /**
     * 明细级部门划转：单条事实 supersede 为新部门（结佣调整 TRANSFER 用）。
     *
     * @param factId       事实 ID
     * @param targetDeptId 目标部门 ID
     * @param operatorId   操作人 ID
     * @param adjustId     调整单 ID
     * @return 新事实 ID
     */
    Long transferFact(Long factId, Long targetDeptId, Long operatorId, Long adjustId);

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
}
