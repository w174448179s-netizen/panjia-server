package com.panjia.contracts.port;

import com.panjia.contracts.dto.HistoryRealFactDTO;
import com.panjia.contracts.dto.PerformanceContractSummaryDTO;
import com.panjia.contracts.dto.PerformanceFactSummaryDTO;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 实收「事实」跨域端口（received 域对外契约）。
 * <p>
 * PERF_REAL（实收/结佣业绩）拆表后物理落在实收域
 * {@code pj_received_detail(rd) + pj_received_contract(rc)}：
 * panjia-performance 的 {@code CommissionPerformanceAdapter} 对所有 PERF_REAL
 * 读/写一律委托本端口实现（{@code ReceivedRealFactAdapter}），
 * <b>pj_perf_fact 不再承载任何 PERF_REAL 运行时读写</b>。
 * <p>
 * ID 约定：本端口返回事实的 {@code factId} 即 {@code pj_received_detail.id}；
 * 结佣明细 {@code performance_fact_id} 对实收口径绑定的就是该 ID。
 * 口径：findActive* 只返回 {@code detail_status='ACTIVE'}；getById 不限状态（溯源用）。
 */
public interface ReceivedRealFactPort {

    /** 按期间 + 门店查 ACTIVE 实收明细（deptId 取 COALESCE(rd.dept_id,rc.dept_id)，含下级部门）。 */
    List<PerformanceFactSummaryDTO> findActiveByDept(String period, Long deptId);

    /** 按期间 + 员工查 ACTIVE 实收明细（rd.employee_id 为空时按员工工号关联兜底）。 */
    List<PerformanceFactSummaryDTO> findActiveByEmployee(String period, Long employeeId);

    /**
     * 按期间 + 订单号/合同号查 ACTIVE 实收明细。
     * <p>orderNo 非空时与 contractNo 双键精确限定（同合同号挂多订单防串单）；
     * 为空时退化 contract_no/order_no 双键 OR 口径。
     */
    List<PerformanceFactSummaryDTO> findActiveByContract(String period, String orderNo, String contractNo);

    /**
     * 按业务键集合（订单号/合同号）查 ACTIVE 实收明细（<b>不限归属期间，跨月</b>）。
     * order_no 或 contract_no 命中键集合即返回。
     */
    List<PerformanceFactSummaryDTO> findActiveByBizKeys(Collection<String> bizKeys);

    /** 按实收明细 ID 集合查 ACTIVE 明细；不存在的 ID 不在结果中。 */
    List<PerformanceFactSummaryDTO> findActiveByIds(Collection<Long> ids);

    /** 按实收明细 ID 查单条（不限状态，溯源链路用；含 REVERSED）；不存在返回 null。 */
    PerformanceFactSummaryDTO getById(Long id);

    /**
     * 按期间查实收「合同」维度汇总（结佣申请列表合并展示用）。
     * 按 COALESCE(rc.order_no, rc.contract_no) 聚合；deptId 非空含下级部门。
     *
     * @param period 归属期间 YYYY-MM；可空，空时跨全部期间聚合（应收取该合同全部期间合计）
     */
    List<PerformanceContractSummaryDTO> listContractSummaries(String period, Long deptId, Long employeeId, String keyword);

    /**
     * 按期间 + 业务键集合（合同号/订单号）统计去重员工数（结佣明细列表跨合同合计用）。
     * 员工键口径与 {@link #listContractSummaries} 的 employeeCount 一致：
     * COALESCE(rd.employee_id, 工号兜底员工 e.employee_id, rd.employee_external_code)；
     * 仅统计 ACTIVE 明细，任一业务键（order_no 或 contract_no）命中即计入。
     *
     * @param period 归属期间 YYYY-MM；可空，空时统计全部期间
     * @return 去重员工数；无匹配返回 0
     */
    long countDistinctEmployeesByKeys(String period, Collection<String> bizKeys);

    /**
     * 结佣发起页主查询：实收<b>审批通过</b>的审批单行（订单维度，一行=一张审批单）。
     * 展示字段取审批单快照，员工数/明细数经实收明细聚合现算；
     * 门店（含下级）与员工过滤经实收明细判定，keyword（合同号/订单号/房源地址）与 bizType 下推 SQL。
     *
     * @param deptId     门店 ID（null 查全部；非 null 含下级部门）
     * @param employeeId 员工 ID（null 不过滤）
     * @param keyword    关键字（可空）
     * @param bizType    业务类型（可空；精确匹配）
     * @return 审批单行（按签约日期倒序，id 次序兜底）；无数据返回空列表
     */
    List<PerformanceContractSummaryDTO> listApprovedApplies(Long deptId, Long employeeId, String keyword, String bizType);

    /**
     * 批量查合同维度「调整前」实收金额合计。
     * <p>
     * 口径：以各业务键当前 ACTIVE rd 的 sourceKey 集合为准，沿明细链（同 source_key，
     * 含历史 REVERSED rd）取 id 最早一条金额求和；未调整时原值=当前合计。
     *
     * @return bizKey → 调整前合计；无 ACTIVE 明细的键不在结果中
     */
    Map<String, BigDecimal> sumOriginalAmountsByKeys(String period, Collection<String> bizKeys);

    /**
     * 判断业务键 ACTIVE 实收明细的 source_key 链是否存在 REVERSED 行
     * （「实收是否已调整」判定用，不限归属期间）。
     * <p>
     * 口径：以各业务键当前 ACTIVE rd 的 source_key 集合为准，任一链上存在 REVERSED
     * 明细即返回 true。2026-10-15 结佣发起闸门用：新签已调整而实收未调整时拦截发起。
     *
     * @param bizKeys 合同号/订单号业务键集合（空集合返回 false，不发起 SQL）
     * @return true = 实收已调整；false = 从未调整
     */
    boolean hasReversedChain(Collection<String> bizKeys);

    /** 历史工资导入批次实收明细（ACTIVE，结佣 LOCKED 建单用；adjust_id 非空的调整新行不计入批次）。 */
    List<HistoryRealFactDTO> listRealFactsByBatch(String period, Long batchId);

    /** 明细级金额调整：单条实收明细 supersede 为 targetAmount。明细不存在返回 null。 */
    Long adjustDetailAmount(Long detailId, BigDecimal targetAmount, Long operatorId, Long adjustId);

    /**
     * 明细级金额 + 角色占比调整（实收调整三类型指定值模式用）：
     * 单条实收明细 supersede 为 targetAmount，shareRatio 非空时同步更新角色占比。明细不存在返回 null。
     */
    Long adjustDetailAmount(Long detailId, BigDecimal targetAmount, BigDecimal shareRatio,
                            Long operatorId, Long adjustId);

    /**
     * 新增实收角色人明细（实收调整三类型「增加角色人」用）：
     * 以模板明细为基准复制合同/期间/费用项/审批单归属等基础字段，覆盖员工/角色/金额/占比，
     * sourceKey 使用 MANUAL-RADJ-{adjustId} 虚拟锚点避开导入幂等键，插入新 ACTIVE 行并刷新合同聚合。
     *
     * @param templateDetailId 模板实收明细 ID（合同既有任一 ACTIVE 明细）
     * @param employeeId       新员工 ID
     * @param employeeCode     新员工工号
     * @param deptId           新部门 ID（null=跟随模板）
     * @param roleType         角色类型
     * @param amount           实收金额
     * @param shareRatio       角色占比（可空）
     * @return 新明细 ID；模板不存在返回 null
     */
    Long addMemberDetail(Long templateDetailId, Long employeeId, String employeeCode, Long deptId,
                         String roleType, BigDecimal amount, BigDecimal shareRatio,
                         Long operatorId, Long adjustId);

    /**
     * 无实收明细时也创建实收明细（2026-10-16 新签调整联动扩展）：
     * 合同已在 {@code pj_received_contract} 有壳但无明细（尚未到账），或连壳都没有（未到账时手工建），
     * 直接按合同号/订单号 + period 定位或创建实收合同壳后插入一条实收明细。
     * <p>
     * 用途：新签调整三类型同额联动实收，当合同整体无实收明细时，仍需为有变动的经纪人自动生成
     * 对应金额的实收明细（虚拟实收），后续真实实收到账直接合并对齐。
     * <p>
     * sourceKey 用 MANUAL-RADJ-{adjustId}-{employeeId} 虚拟锚点，source_type 壳标记为 MANUAL。
     *
     * @param orderNo      订单号（可空）
     * @param contractNo   合同号
     * @param period       归属期间 YYYY-MM（新签事实所在期间）
     * @param bizType      业务类型（新签事实）
     * @param employeeId   员工 ID
     * @param employeeCode 员工工号
     * @param deptId       部门 ID（空则随合同壳）
     * @param roleType     角色类型
     * @param amount       实收金额（delta 同额；可为负）
     * @param shareRatio   角色占比（可空）
     * @return 新明细 ID
     */
    Long createDetailFromContract(String orderNo, String contractNo, String period, String bizType,
                                  Long employeeId, String employeeCode, Long deptId,
                                  String roleType, BigDecimal amount, BigDecimal shareRatio,
                                  Long operatorId, Long adjustId);

    /**
     * 退单联动：按模板实收明细镜像生成一条<b>负</b>实收明细（合同退单口径）。
     * <p>
     * 与 {@link #createDetailFromContract}（新签调整 delta 联动）的区别：
     * <ul>
     *   <li>费用项 / 角色名 / 占比 / 员工 全部镜像模板明细，保证「逐条全额冲退」可溯源；</li>
     *   <li>落账期间取 {@code refundPeriod}，在「(orderNo/contractNo) + refundPeriod」合同壳下建行，
     *       与模板原期间的壳互不影响；</li>
     *   <li>sourceKey 用 {@code MANUAL-RFND-{refundId}-{templateDetailId}} 锚点，避开导入幂等键与
     *       ACTIVE 部分唯一索引；</li>
     *   <li>{@code refund_of_detail_id} 指向模板明细，反查退单来源。</li>
     * </ul>
     *
     * @param templateDetailId 模板实收明细 ID（{@code pj_received_detail.id}）
     * @param refundPeriod     退单落账期间 YYYY-MM（负明细归属月）
     * @param operatorId       执行人 ID
     * @param refundId         退单申请单 ID
     * @return 新负明细 ID；模板不存在或非 ACTIVE 返回 null
     */
    Long createRefundDetail(Long templateDetailId, String refundPeriod, Long operatorId, Long refundId);

    /**
     * 统计某合同号在实收合同表（全局，不限期间/批次）的去重非空订单号数量。
     * <p>
     * 双键未命中退化按合同号归属的闸门组成部分：新签事实订单唯一 <b>且</b> 实收合同订单唯一
     * 才允许退化（兼容新签 order_no 误填成合同号），防止同合同多订单/分批到账单重复归属。
     *
     * @param contractNo 合同号
     * @return 去重非空订单号数量
     */
    long countDistinctReceivedOrders(String contractNo);
}
