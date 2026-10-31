package com.panjia.received.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.panjia.contracts.dto.EmployeeMainDataDTO;
import com.panjia.contracts.dto.NormalizedRecordDTO;
import com.panjia.contracts.port.ImportNormalizedRecordQueryPort;
import com.panjia.contracts.port.ImportToReceivedPort;
import com.panjia.contracts.port.CommissionPerformanceQueryPort;
import com.panjia.contracts.port.CommissionConsumptionQueryPort;
import com.panjia.contracts.port.ImportBatchWritePort;
import com.panjia.contracts.port.PeopleQueryPort;
import com.panjia.contracts.port.PeriodCloseQueryPort;
import com.panjia.received.domain.ReceivedContract;
import com.panjia.received.domain.ReceivedDetail;
import com.panjia.received.mapper.ReceivedContractMapper;
import com.panjia.received.mapper.ReceivedDetailMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.domain.PageResult;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 导入批次 → 实收表写入实现（panjia-received 域）。
 * <p>
 * KE_RECEIVED / HISTORY_PAYROLL 导入批次不再经过 PerformanceEngine 建 PERF_REAL，
 * 由本类直接写入实收域两张表（pj_received_contract + pj_received_detail）。
 * <p>
 * 幂等查询走 LambdaQueryWrapper（BaseMapper.selectOne），不声明 Mapper 自定义方法——
 * 单表等值查询没有手写 SQL 的必要，也杜绝「声明了方法没绑语句」的 BindingException。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ImportToReceivedServiceImpl implements ImportToReceivedPort {

    private static final String DETAIL_STATUS_ACTIVE = "ACTIVE";
    private static final String DETAIL_STATUS_SUPERSEDED = "SUPERSEDED";
    /** 问题清单类型：订单已审批/已结佣，本批变更未导入（与 ImportIssueType.RECEIVED_CONSUMED_SKIPPED 同码） */
    private static final String ISSUE_TYPE_CONSUMED_SKIPPED = "RECEIVED_CONSUMED_SKIPPED";

    private static final int PAGE_SIZE = 500;

    private final ImportNormalizedRecordQueryPort importQueryPort;
    private final ReceivedContractMapper contractMapper;
    private final ReceivedDetailMapper detailMapper;
    private final PeopleQueryPort peopleQueryPort;
    private final CommissionPerformanceQueryPort commissionPerformanceQueryPort;
    /** 期间封账查询（performance 域实现，pj_perf_period_close 为唯一封账事实源） */
    private final PeriodCloseQueryPort periodCloseQueryPort;
    /** 结佣消费判定（commission 域实现）：增量导入按订单粒度判定，避免整批拒绝 */
    private final CommissionConsumptionQueryPort commissionConsumptionQueryPort;
    /** 导入域问题清单回写（已结佣订单跳过的明细清单，前端可见） */
    private final ImportBatchWritePort importBatchWritePort;
    /** 实收审批单（临时依赖 performance 域，后续下沉到实收域） */
    private final com.panjia.performance.mapper.ReceivedApplyMapper applyMapper;
    /** 新签-实收人员对齐补行（与新签到达补建单链路共用同一实现） */
    private final com.panjia.received.support.ReceivedSupplementSupport supplementSupport;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ImportToReceivedResult consumeBatch(Long batchId, String period, String sourceType, Long operatorId) {
        log.info("[实收写入] 开始：batchId={}, sourceType={}, period={}", batchId, sourceType, period);
        // 封账校验：与新签侧 PerformanceEngine.buildFromBatch 口径一致，已封账期间禁止写实收事实；
        // 历史工资为存量补录，豁免（历史期间早已封账）
        if (period != null && !period.isBlank()
                && !"HISTORY_PAYROLL".equals(sourceType)
                && periodCloseQueryPort.isClosed(period)) {
            throw new IllegalStateException("期间已封账，禁止写入实收：period=" + period);
        }
        if ("KE_RECEIVED".equals(sourceType)) {
            return consumeKeReceivedMerge(batchId, period, sourceType, operatorId);
        }
        return consumeFullReplace(batchId, period, sourceType, operatorId);
    }

    /**
     * 全量替换写入（HISTORY_PAYROLL 等非增量来源）。
     * <p>
     * 语义保持原样：旧批次已由 import 域冲销（明细置 SUPERSEDED），本批按行幂等插入。
     */
    private ImportToReceivedResult consumeFullReplace(Long batchId, String period, String sourceType, Long operatorId) {
        int newContracts = 0;
        int newDetails = 0;
        int skippedRefundRows = 0;
        int pageNum = 1;
        Map<String, ReceivedContract> contractCache = new LinkedHashMap<>();

        PageResult<NormalizedRecordDTO> pageResult;
        do {
            pageResult = importQueryPort.listByBatchId(batchId, pageNum, PAGE_SIZE);
            if (pageResult == null || pageResult.getRows() == null) break;

            // 按页批量预查员工主数据（回填明细/合同 dept_id：部门权限过滤依赖，
            // 不写会导致总监/财务等非超管角色实收合计恒为 0）
            Set<String> pageCodes = new HashSet<>();
            for (NormalizedRecordDTO record : pageResult.getRows()) {
                if (isReceivedRecord(record, sourceType) && record.getEmployeeCode() != null) {
                    pageCodes.add(record.getEmployeeCode());
                }
            }
            Map<String, EmployeeMainDataDTO> empMap = peopleQueryPort.findEmployeeMainDataByCodes(pageCodes);

            // 页级批量预查「已手工退单」业务键（2026-10-25 互斥）：手工退单执行时已镜像生成
            // 负实收明细，此时再写入贝壳导入的负到账行即重复冲减，这些行必须跳过
            Set<String> negativeKeys = new HashSet<>();
            for (NormalizedRecordDTO record : pageResult.getRows()) {
                if (!isReceivedRecord(record, sourceType)) continue;
                BigDecimal amt = record.getReceivedAmount();
                if (amt != null && amt.signum() < 0) {
                    if (record.getOrderNo() != null) negativeKeys.add(record.getOrderNo());
                    if (record.getContractNo() != null) negativeKeys.add(record.getContractNo());
                }
            }
            Set<String> refundedKeys = negativeKeys.isEmpty()
                ? Set.of() : commissionPerformanceQueryPort.findManualRefundedBizKeys(negativeKeys);

            for (NormalizedRecordDTO record : pageResult.getRows()) {
                if (!isReceivedRecord(record, sourceType)) continue;

                // 负到账行 + 合同已手工退单 → 跳过（避免同一笔退单被冲减两次）
                BigDecimal rowAmount = record.getReceivedAmount();
                if (rowAmount != null && rowAmount.signum() < 0
                    && (refundedKeys.contains(record.getOrderNo()) || refundedKeys.contains(record.getContractNo()))) {
                    log.warn("[实收写入] 合同已手工退单，跳过导入的负到账行：contractNo={}, orderNo={}, recordId={}",
                        record.getContractNo(), record.getOrderNo(), record.getId());
                    skippedRefundRows++;
                    continue;
                }

                EmployeeMainDataDTO emp = record.getEmployeeCode() == null ? null : empMap.get(record.getEmployeeCode());

                // 幂等检查：source_key 已存在 ACTIVE 明细 → 跳过（uk_received_detail_anchor 保证至多一条）
                String sourceKey = buildDetailSourceKey(record, period);
                boolean exists = detailMapper.selectCount(new LambdaQueryWrapper<ReceivedDetail>()
                    .eq(ReceivedDetail::getSourceKey, sourceKey)
                    .eq(ReceivedDetail::getDetailStatus, DETAIL_STATUS_ACTIVE)) > 0;
                if (exists) continue;

                // 按 orderNo + period + sourceType 查/建合同（uk_received_contract_anchor 保证至多一条）
                ReceivedContract contract = contractCache.computeIfAbsent(
                    buildContractAnchor(record, period, sourceType),
                    key -> contractMapper.selectOne(new LambdaQueryWrapper<ReceivedContract>()
                        .eq(ReceivedContract::getOrderNo, record.getOrderNo())
                        .eq(ReceivedContract::getPeriod, period)
                        .eq(ReceivedContract::getSourceType, sourceType)
                        .last("LIMIT 1")));
                if (contract == null) {
                    contract = buildContract(record, period, sourceType, batchId, emp);
                    contractMapper.insert(contract);
                    newContracts++;
                    log.info("[实收写入] 新建合同：id={}, orderNo={}, period={}",
                        contract.getId(), contract.getOrderNo(), period);
                }

                // 建明细（合同合计不在此增量累加，循环结束后按 ACTIVE 明细统一重算）
                ReceivedDetail detail = buildDetail(record, contract.getId(), period, sourceKey, operatorId, emp);
                detailMapper.insert(detail);
                newDetails++;
            }
            pageNum++;
        } while (pageResult.getRows() != null && !pageResult.getRows().isEmpty());

        // 回写合同合计：按 ACTIVE 明细重算，不在旧值上累加——
        // 重导时旧批次明细已置 SUPERSEDED，累加会把旧批金额重复计入
        refreshContractAggregates(contractCache.values().stream()
            .map(ReceivedContract::getId)
            .filter(java.util.Objects::nonNull)
            .toList());

        log.info("[实收写入] 完成（全量替换）：batchId={}, newContracts={}, newDetails={}, skippedRefundRows={}",
            batchId, newContracts, newDetails, skippedRefundRows);
        return new ImportToReceivedResult(newContracts, newDetails, skippedRefundRows, 0, 0, 0);
    }

    /**
     * KE_RECEIVED 增量合并写入（2026-10-29）。
     * <p>
     * 业务方每几天导一次补充到账，不能按整批替换/整批拒绝。本方法按「订单 + 归属月」分组：
     * <ol>
     *   <li>组内明细与本批完全一致 → 整组跳过，不写任何行（避免每天重写同一批数据）；</li>
     *   <li>有变化且该订单已审批/已结佣 → 整组跳过 + 写问题清单（不让一颗已结佣单拖死整批）；</li>
     *   <li>有变化且未消费 → 该订单旧 ACTIVE 明细置 SUPERSEDED（记录被本批替换），再插入本批新行。</li>
     * </ol>
     * 指纹比对只看文件行（{@code normalizedRecordId} 非空）；补行（normalizedRecordId 为空）是
     * 派生数据，不参与比对，替换时随旧行一起 SUPERSEDED 后重新生成。
     */
    private ImportToReceivedResult consumeKeReceivedMerge(Long batchId, String period, String sourceType, Long operatorId) {
        // === 阶段 1：收集本批有效行（整批一次读入，后续全部内存分组，避免逐页重复查库）===
        List<NormalizedRecordDTO> allRows = new ArrayList<>();
        PageResult<NormalizedRecordDTO> pageResult;
        int pageNum = 1;
        do {
            pageResult = importQueryPort.listByBatchId(batchId, pageNum, PAGE_SIZE);
            if (pageResult == null || pageResult.getRows() == null) break;
            for (NormalizedRecordDTO r : pageResult.getRows()) {
                if (isReceivedRecord(r, sourceType)) allRows.add(r);
            }
            pageNum++;
        } while (!pageResult.getRows().isEmpty());
        if (allRows.isEmpty()) {
            return new ImportToReceivedResult(0, 0, 0, 0, 0, 0);
        }

        // 阶段 1.1：手工退单互斥（整批一次性预查）
        Set<String> negativeKeys = new HashSet<>();
        for (NormalizedRecordDTO r : allRows) {
            BigDecimal amt = r.getReceivedAmount();
            if (amt != null && amt.signum() < 0) {
                if (r.getOrderNo() != null) negativeKeys.add(r.getOrderNo());
                if (r.getContractNo() != null) negativeKeys.add(r.getContractNo());
            }
        }
        Set<String> refundedKeys = negativeKeys.isEmpty()
            ? Set.of() : commissionPerformanceQueryPort.findManualRefundedBizKeys(negativeKeys);

        List<NormalizedRecordDTO> rows = new ArrayList<>(allRows.size());
        int skippedRefundRows = 0;
        for (NormalizedRecordDTO r : allRows) {
            BigDecimal amt = r.getReceivedAmount();
            if (amt != null && amt.signum() < 0
                && (refundedKeys.contains(r.getOrderNo()) || refundedKeys.contains(r.getContractNo()))) {
                log.warn("[实收写入] 合同已手工退单，跳过导入的负到账行：contractNo={}, orderNo={}, recordId={}",
                    r.getContractNo(), r.getOrderNo(), r.getId());
                skippedRefundRows++;
                continue;
            }
            if (isBlank(r.getOrderNo()) && isBlank(r.getContractNo())) {
                log.warn("[实收写入] 订单号与合同号均为空，跳过：recordId={}", r.getId());
                continue;
            }
            rows.add(r);
        }
        if (rows.isEmpty()) {
            return new ImportToReceivedResult(0, 0, skippedRefundRows, 0, 0, 0);
        }

        // 阶段 1.2：员工主数据一次预查（dept_id 回填依赖，部门权限过滤）
        Set<String> codes = new HashSet<>();
        for (NormalizedRecordDTO r : rows) {
            if (r.getEmployeeCode() != null) codes.add(r.getEmployeeCode());
        }
        Map<String, EmployeeMainDataDTO> empMap = codes.isEmpty()
            ? Map.of() : peopleQueryPort.findEmployeeMainDataByCodes(codes);

        // === 阶段 2：按 订单号（缺失退合同号）分组 ===
        Map<String, List<NormalizedRecordDTO>> groups = new LinkedHashMap<>();
        for (NormalizedRecordDTO r : rows) {
            groups.computeIfAbsent(buildGroupKey(r), k -> new ArrayList<>()).add(r);
        }

        // 阶段 2.1：批量加载现状（合同 + ACTIVE 明细），一次性查询避免逐组查库
        Set<String> orders = new LinkedHashSet<>();
        Set<String> contractNos = new LinkedHashSet<>();
        for (NormalizedRecordDTO r : rows) {
            if (!isBlank(r.getOrderNo())) orders.add(r.getOrderNo().trim());
            if (!isBlank(r.getContractNo())) contractNos.add(r.getContractNo().trim());
        }
        Map<String, ReceivedContract> contractByGroupKey = loadExistingContracts(period, sourceType, orders, contractNos);
        Map<Long, List<ReceivedDetail>> activeDetailsByContract = loadActiveDetails(contractByGroupKey.values());

        // 阶段 2.2：指纹比对，挑出「有变化」的组
        Map<String, List<NormalizedRecordDTO>> changedGroups = new LinkedHashMap<>();
        int skippedUnchangedGroups = 0;
        for (Map.Entry<String, List<NormalizedRecordDTO>> entry : groups.entrySet()) {
            ReceivedContract existing = contractByGroupKey.get(entry.getKey());
            if (existing == null) {
                changedGroups.put(entry.getKey(), entry.getValue());
                continue;
            }
            List<ReceivedDetail> dbRows = activeDetailsByContract.getOrDefault(existing.getId(), List.of());
            boolean hasFileDerivedRow = dbRows.stream().anyMatch(d -> d.getNormalizedRecordId() != null);
            if (hasFileDerivedRow && importFingerprints(entry.getValue(), period)
                .equals(dbFingerprints(dbRows))) {
                skippedUnchangedGroups++;
                continue;
            }
            changedGroups.put(entry.getKey(), entry.getValue());
        }
        if (changedGroups.isEmpty()) {
            log.info("[实收写入] 完成（增量合并）：batchId={}, 全部 {} 组明细无变化，未写库",
                batchId, skippedUnchangedGroups);
            return new ImportToReceivedResult(0, 0, skippedRefundRows, 0, skippedUnchangedGroups, 0);
        }

        // 阶段 2.3：已消费判定（一次批量：结佣域 + 实收审批单 + 明细调整痕迹）
        Map<Long, List<String>> bizKeysByContractId = new LinkedHashMap<>();
        for (Map.Entry<String, ReceivedContract> entry : contractByGroupKey.entrySet()) {
            if (entry.getValue().getId() == null) continue;
            bizKeysByContractId.computeIfAbsent(entry.getValue().getId(), k -> new ArrayList<>())
                .add(entry.getKey());
        }
        Map<String, String> consumedReasons = loadConsumedReasons(period, changedGroups,
            activeDetailsByContract, bizKeysByContractId);

        // === 阶段 3：逐组分流（跳过已消费 / 替换未消费） ===
        int newContracts = 0;
        int newDetails = 0;
        int skippedConsumedGroups = 0;
        Map<String, ReceivedContract> replacedContracts = new LinkedHashMap<>();
        for (Map.Entry<String, List<NormalizedRecordDTO>> entry : changedGroups.entrySet()) {
            List<NormalizedRecordDTO> groupRows = entry.getValue();
            String reason = findConsumedReason(consumedReasons, groupRows);
            if (reason != null) {
                String orderNo = groupRows.get(0).getOrderNo() != null
                    ? groupRows.get(0).getOrderNo() : groupRows.get(0).getContractNo();
                String msg = "订单 " + orderNo + " 已审批/已结佣（" + reason + "），本次新增或变更 "
                    + groupRows.size() + " 行未导入，请通过业绩调整处理";
                log.info("[实收写入] 已消费跳过：batchId={}, {}", batchId, msg);
                importBatchWritePort.recordBatchIssue(batchId, ISSUE_TYPE_CONSUMED_SKIPPED, msg);
                skippedConsumedGroups++;
                continue;
            }

            NormalizedRecordDTO first = groupRows.get(0);
            ReceivedContract contract = contractByGroupKey.get(entry.getKey());
            if (contract == null) {
                EmployeeMainDataDTO emp = first.getEmployeeCode() == null ? null : empMap.get(first.getEmployeeCode());
                contract = buildContract(first, period, sourceType, batchId, emp);
                contractMapper.insert(contract);
                newContracts++;
                log.info("[实收写入] 新建合同：id={}, orderNo={}, period={}",
                    contract.getId(), contract.getOrderNo(), period);
            } else {
                supersedeActiveDetails(activeDetailsByContract.getOrDefault(contract.getId(), List.of()), batchId);
            }

            // 插入本批新行：source_key 用业务键 + 组内同键序号（旧行已 SUPERSEDED，不占用 ACTIVE 锚点）
            Map<String, Integer> seqByBase = new LinkedHashMap<>();
            for (NormalizedRecordDTO r : groupRows) {
                String base = buildDetailBaseKey(r, period);
                int seq = seqByBase.merge(base, 1, Integer::sum);
                String sourceKey = base + "|" + seq;
                EmployeeMainDataDTO emp = r.getEmployeeCode() == null ? null : empMap.get(r.getEmployeeCode());
                detailMapper.insert(buildDetail(r, contract.getId(), period, sourceKey, operatorId, emp));
                newDetails++;
            }
            replacedContracts.put(entry.getKey(), contract);
        }

        // === 阶段 4：先重算聚合（补行依赖合同实收合计），再做人员对齐补行，最后再重算一次 ===
        List<Long> touchedContractIds = replacedContracts.values().stream()
            .map(ReceivedContract::getId)
            .filter(java.util.Objects::nonNull)
            .toList();
        refreshContractAggregates(touchedContractIds);
        int supplementedDetails = supplementMissingEmployees(batchId, period, replacedContracts, operatorId);
        refreshContractAggregates(touchedContractIds);

        log.info("[实收写入] 完成（增量合并）：batchId={}, newContracts={}, newDetails={}, "
                + "skippedRefundRows={}, supplementedDetails={}, skippedUnchangedGroups={}, skippedConsumedGroups={}",
            batchId, newContracts, newDetails, skippedRefundRows, supplementedDetails,
            skippedUnchangedGroups, skippedConsumedGroups);
        return new ImportToReceivedResult(newContracts, newDetails, skippedRefundRows,
            supplementedDetails, skippedUnchangedGroups, skippedConsumedGroups);
    }

    /** 组键：优先订单号，缺失退合同号；带前缀区分两个命名空间。 */
    private String buildGroupKey(NormalizedRecordDTO r) {
        return isBlank(r.getOrderNo()) ? "C|" + r.getContractNo().trim() : "O|" + r.getOrderNo().trim();
    }

    /** 批量加载同期间同来源的合同，按组键索引（订单号优先，合同号兜底）。 */
    private Map<String, ReceivedContract> loadExistingContracts(String period, String sourceType,
                                                                Set<String> orders, Set<String> contractNos) {
        LambdaQueryWrapper<ReceivedContract> wrapper = new LambdaQueryWrapper<ReceivedContract>()
            .eq(ReceivedContract::getPeriod, period)
            .eq(ReceivedContract::getSourceType, sourceType);
        if (!orders.isEmpty() && !contractNos.isEmpty()) {
            wrapper.and(w -> w.in(ReceivedContract::getOrderNo, orders)
                .or().in(ReceivedContract::getContractNo, contractNos));
        } else if (!orders.isEmpty()) {
            wrapper.in(ReceivedContract::getOrderNo, orders);
        } else if (!contractNos.isEmpty()) {
            wrapper.in(ReceivedContract::getContractNo, contractNos);
        } else {
            return Map.of();
        }
        Map<String, ReceivedContract> result = new LinkedHashMap<>();
        for (ReceivedContract c : contractMapper.selectList(wrapper)) {
            if (!isBlank(c.getOrderNo())) {
                result.putIfAbsent("O|" + c.getOrderNo().trim(), c);
            }
            if (!isBlank(c.getContractNo())) {
                result.putIfAbsent("C|" + c.getContractNo().trim(), c);
            }
        }
        return result;
    }

    /** 一次查询取全部合同的 ACTIVE 明细，按合同 ID 分组。 */
    private Map<Long, List<ReceivedDetail>> loadActiveDetails(java.util.Collection<ReceivedContract> contracts) {
        List<Long> ids = contracts.stream().map(ReceivedContract::getId)
            .filter(java.util.Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<Long, List<ReceivedDetail>> result = new LinkedHashMap<>();
        for (ReceivedDetail d : detailMapper.selectList(new LambdaQueryWrapper<ReceivedDetail>()
            .in(ReceivedDetail::getContractId, ids)
            .eq(ReceivedDetail::getDetailStatus, DETAIL_STATUS_ACTIVE))) {
            result.computeIfAbsent(d.getContractId(), k -> new ArrayList<>()).add(d);
        }
        return result;
    }

    /** 已消费原因：结佣域（跨域端口，一次批量）+ 实收审批单 SUBMITTED/APPROVED + 明细带调整痕迹。 */
    private Map<String, String> loadConsumedReasons(String period,
                                                    Map<String, List<NormalizedRecordDTO>> changedGroups,
                                                    Map<Long, List<ReceivedDetail>> activeDetailsByContract,
                                                    Map<Long, List<String>> bizKeysByContractId) {
        Set<String> contractNos = new LinkedHashSet<>();
        Set<String> orders = new LinkedHashSet<>();
        for (List<NormalizedRecordDTO> groupRows : changedGroups.values()) {
            for (NormalizedRecordDTO r : groupRows) {
                if (!isBlank(r.getContractNo())) contractNos.add(r.getContractNo().trim());
                if (!isBlank(r.getOrderNo())) orders.add(r.getOrderNo().trim());
            }
        }

        // 按本批归属月过滤：同一订单其他月份已出结佣单，不构成本月实收不可导入的理由
        Map<String, String> reasons = new LinkedHashMap<>(
            commissionConsumptionQueryPort.findConsumedBizKeys(period, contractNos, orders));

        // 实收审批单：已提交/已通过的单不再接受新明细（autoCreate 只会合并草稿/提交单），
        // 故整单对应的订单视为已消费
        LambdaQueryWrapper<com.panjia.performance.domain.ReceivedApply> applyQuery =
            new LambdaQueryWrapper<com.panjia.performance.domain.ReceivedApply>()
                .eq(com.panjia.performance.domain.ReceivedApply::getPeriod, period)
                .in(com.panjia.performance.domain.ReceivedApply::getStatus,
                    com.panjia.performance.domain.ReceivedApplyStatus.SUBMITTED,
                    com.panjia.performance.domain.ReceivedApplyStatus.APPROVED);
        if (!contractNos.isEmpty() && !orders.isEmpty()) {
            applyQuery.and(w -> w.in(com.panjia.performance.domain.ReceivedApply::getContractNo, contractNos)
                .or().in(com.panjia.performance.domain.ReceivedApply::getOrderNo, orders));
        } else if (!contractNos.isEmpty()) {
            applyQuery.in(com.panjia.performance.domain.ReceivedApply::getContractNo, contractNos);
        } else if (!orders.isEmpty()) {
            applyQuery.in(com.panjia.performance.domain.ReceivedApply::getOrderNo, orders);
        }
        for (com.panjia.performance.domain.ReceivedApply apply : applyMapper.selectList(applyQuery)) {
            // 新签调整联动直建的 0 元 APPROVED 单不产生真实到账：不能据此把整组判为已消费，
            // 否则真实实收永远导不进来（2026-10-31）
            BigDecimal receivedAmount = apply.getReceivedAmount();
            if (receivedAmount == null || receivedAmount.signum() == 0) {
                continue;
            }
            String reason = "实收审批单" + apply.getStatus();
            if (!isBlank(apply.getContractNo())) {
                reasons.putIfAbsent("C|" + apply.getContractNo().trim(), reason);
            }
            if (!isBlank(apply.getOrderNo())) {
                reasons.putIfAbsent("O|" + apply.getOrderNo().trim(), reason);
            }
        }

        // 明细带调整痕迹（adjustId 非空）：已被调整单引用，整组不允许替换。
        // 例外（2026-10-31）：纯「新签调整联动」虚拟行（无来源批次、无归一化记录）且该合同净额为 0 时，
        // 这组行只用于人员对齐、不承载金额，允许真实到账导入替换。
        for (Map.Entry<Long, List<ReceivedDetail>> entry : activeDetailsByContract.entrySet()) {
            List<ReceivedDetail> details = entry.getValue();
            boolean adjusted = details.stream().anyMatch(d -> d.getAdjustId() != null);
            if (!adjusted) continue;
            boolean hasRealAdjustRow = details.stream().anyMatch(d -> d.getAdjustId() != null
                && (d.getSourceBatchId() != null || d.getNormalizedRecordId() != null));
            BigDecimal virtualAdjustNet = details.stream()
                .filter(d -> d.getAdjustId() != null
                    && d.getSourceBatchId() == null
                    && d.getNormalizedRecordId() == null)
                .map(d -> d.getPerformanceAmount() == null ? BigDecimal.ZERO : d.getPerformanceAmount())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
            if (!hasRealAdjustRow && virtualAdjustNet.signum() == 0) {
                continue;
            }
            for (String bizKey : bizKeysByContractId.getOrDefault(entry.getKey(), List.of())) {
                reasons.putIfAbsent(bizKey, "明细已被调整单引用");
            }
        }

        // 跨月已出单（2026-10-29）：新签到达后补建单会把同订单多个归属月的未绑定到账并进一张单，
        // 那张单的归属月可能不是本批归属月。此时替换明细会改掉已出单的金额且不做回算 → 视为已消费。
        Set<Long> boundApplyIds = new LinkedHashSet<>();
        for (List<ReceivedDetail> details : activeDetailsByContract.values()) {
            for (ReceivedDetail d : details) {
                if (d.getReceivedApplyId() != null) {
                    boundApplyIds.add(d.getReceivedApplyId());
                }
            }
        }
        if (!boundApplyIds.isEmpty()) {
            Map<Long, com.panjia.performance.domain.ReceivedApplyStatus> statusByApplyId = new HashMap<>();
            Set<Long> zeroAmountApplyIds = new LinkedHashSet<>();
            for (com.panjia.performance.domain.ReceivedApply a : applyMapper.selectList(
                    new LambdaQueryWrapper<com.panjia.performance.domain.ReceivedApply>()
                        .in(com.panjia.performance.domain.ReceivedApply::getId, boundApplyIds)
                        .select(com.panjia.performance.domain.ReceivedApply::getId,
                            com.panjia.performance.domain.ReceivedApply::getStatus,
                            com.panjia.performance.domain.ReceivedApply::getReceivedAmount))) {
                statusByApplyId.put(a.getId(), a.getStatus());
                // 0 元联动单不承载真实到账：绑定其上的虚拟明细不构成「跨月已出单」
                BigDecimal amt = a.getReceivedAmount();
                if (amt == null || amt.signum() == 0) {
                    zeroAmountApplyIds.add(a.getId());
                }
            }
            for (Map.Entry<Long, List<ReceivedDetail>> entry : activeDetailsByContract.entrySet()) {
                boolean boundToLiveApply = entry.getValue().stream()
                    .filter(d -> d.getReceivedApplyId() != null
                        && !zeroAmountApplyIds.contains(d.getReceivedApplyId()))
                    .map(ReceivedDetail::getReceivedApplyId)
                    .map(statusByApplyId::get)
                    .anyMatch(s -> s == com.panjia.performance.domain.ReceivedApplyStatus.SUBMITTED
                        || s == com.panjia.performance.domain.ReceivedApplyStatus.APPROVED);
                if (!boundToLiveApply) continue;
                for (String bizKey : bizKeysByContractId.getOrDefault(entry.getKey(), List.of())) {
                    reasons.putIfAbsent(bizKey, "实收单已提交/已通过（跨月累计出单）");
                }
            }
        }
        return reasons;
    }

    /** 组内任一业务键已消费即返回原因；未消费返回 null。 */
    private String findConsumedReason(Map<String, String> consumedReasons, List<NormalizedRecordDTO> groupRows) {
        for (NormalizedRecordDTO r : groupRows) {
            if (!isBlank(r.getOrderNo())) {
                String reason = consumedReasons.get("O|" + r.getOrderNo().trim());
                if (reason != null) return reason;
            }
            if (!isBlank(r.getContractNo())) {
                String reason = consumedReasons.get("C|" + r.getContractNo().trim());
                if (reason != null) return reason;
            }
        }
        return null;
    }

    /** 旧 ACTIVE 明细置 SUPERSEDED，并记录「被本批替换」，撤销本批时据此精确恢复。 */
    private void supersedeActiveDetails(List<ReceivedDetail> details, Long batchId) {
        for (ReceivedDetail d : details) {
            // 新签补录行（supplementOriginBatchId 非空）是派生数据：文件为权威，重导必须一并替换，
            // 否则残留的补录行会让组实收合计虚增（它没有 sourceBatchId，不会被下面的来源判定命中）
            boolean derivedSupplement = d.getSupplementOriginBatchId() != null;
            // 只替换导入来源的行；手工调整/手工退单镜像行（sourceBatchId 为空）不属于任何批次，
            // 替换它们会让撤销回滚失效，故保留为 ACTIVE（其存在已由 adjustId 判定拦截整组）
            if (d.getSourceBatchId() == null && !derivedSupplement) {
                continue;
            }
            d.setDetailStatus(DETAIL_STATUS_SUPERSEDED);
            d.setSupersededByBatchId(batchId);
            detailMapper.updateById(d);
        }
    }

    /**
     * 导入侧组指纹：人员/角色/费项多重集 + 组到账总额。
     * <p>
     * 不逐行比金额：补行会对已有行「等比例压缩」并把差额分摊给新签缺失人（组总额不变），
     * 逐行比对会让"文件没变"被判成"有变化"从而每次重导都重写。用「人员集合 + 总额」比对，
     * 文件未变时稳定命中跳过；行级金额在人员内部被重分配属既有补行语义，不视为变化。
     */
    private List<String> importFingerprints(List<NormalizedRecordDTO> rows, String period) {
        List<String> fp = new ArrayList<>(rows.size());
        BigDecimal total = BigDecimal.ZERO;
        for (NormalizedRecordDTO r : rows) {
            fp.add((r.getEmployeeCode() != null ? r.getEmployeeCode() : "") + "|"
                + (r.getRoleType() != null ? r.getRoleType() : "") + "|"
                + (r.getFeeItem() != null ? r.getFeeItem() : ""));
            total = total.add(r.getReceivedAmount() != null ? r.getReceivedAmount() : BigDecimal.ZERO);
        }
        java.util.Collections.sort(fp);
        fp.add("T|" + normalizeAmount(total));
        return fp;
    }

    /**
     * 库内组指纹：文件派生行（{@code normalizedRecordId} 非空）的人员/角色/费项多重集 + 全部 ACTIVE 行总额。
     * <p>
     * 总额含补行（补行是把文件总额按人重新分配，不改组总额），因此与导入侧总额可直接比对。
     */
    private List<String> dbFingerprints(List<ReceivedDetail> details) {
        List<String> fp = new ArrayList<>(details.size());
        BigDecimal total = BigDecimal.ZERO;
        for (ReceivedDetail d : details) {
            total = total.add(d.getPerformanceAmount() != null ? d.getPerformanceAmount() : BigDecimal.ZERO);
            if (d.getNormalizedRecordId() == null) continue;
            fp.add((d.getEmployeeExternalCode() != null ? d.getEmployeeExternalCode() : "") + "|"
                + (d.getRoleType() != null ? d.getRoleType() : "") + "|"
                + (d.getFeeItem() != null ? d.getFeeItem() : ""));
        }
        java.util.Collections.sort(fp);
        fp.add("T|" + normalizeAmount(total));
        return fp;
    }

    private String normalizeAmount(BigDecimal amount) {
        return (amount == null ? BigDecimal.ZERO : amount).stripTrailingZeros().toPlainString();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void supersedeBatches(java.util.List<Long> oldBatchIds) {
        if (oldBatchIds == null || oldBatchIds.isEmpty()) {
            return;
        }
        // 冲销前先快照受影响的合同（冲销后明细状态已变，查不到）
        List<Long> affectedContractIds = detailMapper.selectActiveContractIdsByBatchIds(oldBatchIds);
        detailMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<ReceivedDetail>()
            .in(ReceivedDetail::getSourceBatchId, oldBatchIds)
            .eq(ReceivedDetail::getDetailStatus, DETAIL_STATUS_ACTIVE)
            .set(ReceivedDetail::getDetailStatus, "SUPERSEDED"));
        // 重算受影响合同聚合：若新批次文件里不再含该合同，旧合计必须清零，不能残留
        refreshContractAggregates(affectedContractIds);
        log.info("[实收写入] 重复导入冲销旧批次实收明细：oldBatchIds={}, affectedContracts={}",
            oldBatchIds, affectedContractIds.size());
    }

    /**
     * 按 ACTIVE 明细重算合同级聚合（period_total_received / item_count）。
     * <p>
     * 一次 GROUP BY 取聚合后统一回写，避免逐合同查询；
     * 重导/跨部门冲销时旧批次明细已置 SUPERSEDED，不会被计入。
     */
    private void refreshContractAggregates(java.util.Collection<Long> contractIds) {
        if (contractIds == null || contractIds.isEmpty()) {
            return;
        }
        Map<Long, Map<String, Object>> aggregateByContract = new LinkedHashMap<>();
        for (Map<String, Object> row : detailMapper.selectActiveAggregatesByContractIds(contractIds)) {
            aggregateByContract.put(((Number) row.get("contractId")).longValue(), row);
        }
        List<ReceivedContract> contracts = contractMapper.selectList(
            new LambdaQueryWrapper<ReceivedContract>().in(ReceivedContract::getId, contractIds));
        for (ReceivedContract c : contracts) {
            Map<String, Object> agg = aggregateByContract.get(c.getId());
            Object total = agg == null ? null : agg.get("totalAmount");
            Object count = agg == null ? null : agg.get("itemCount");
            c.setPeriodTotalReceived(total == null ? BigDecimal.ZERO : new BigDecimal(total.toString()));
            c.setItemCount(count == null ? 0 : ((Number) count).intValue());
            contractMapper.updateById(c);
        }
    }

    private boolean isReceivedRecord(NormalizedRecordDTO record, String sourceType) {
        if (record == null || record.getRecordType() == null) return false;
        if ("KE_RECEIVED".equals(sourceType)) {
            return "KE_RECEIVED".equals(record.getRecordType());
        }
        if ("HISTORY_PAYROLL".equals(sourceType)) {
            return "HIST_REAL".equals(record.getRecordType());
        }
        return false;
    }

    private String buildContractAnchor(NormalizedRecordDTO r, String period, String sourceType) {
        return (r.getOrderNo() != null ? r.getOrderNo() : "") + "|" + period + "|" + sourceType;
    }

    /**
     * 实收明细幂等键。
     * <p>
     * 必须包含【角色】（和费项）：同一员工在同一合同当月可兼任多个角色（如姚宇同时是
     * 房源维护人/钥匙人/推广等 6 个角色），若键里不含角色，同员工的多行 source_key 完全
     * 相同，{@code uk_received_detail_anchor} 幂等检查会把除首行外的角色全部吞掉。
     * <p>
     * 粒度：订单 + 合同 + 员工 + 费项 + 角色 + 期间。
     * <p>
     * 追加归一化记录 ID：同一合同同人同角色可能有多笔到账（贝壳理房通明细按到账笔数
     * 拆行，含空角色回填客源成交人的行），若仅按业务键去重会把后续到账行跳过丢金额；
     * 重导时旧批次被 supersede（旧明细置 SUPERSEDED），故以归一化记录 ID 保证行级唯一。
     */
    private String buildDetailSourceKey(NormalizedRecordDTO r, String period) {
        String base = buildDetailBaseKey(r, period);
        // 同一业务键可能有多笔到账，追加归一化记录 ID 保证行级幂等唯一
        if (r.getId() != null) {
            return base + "|" + r.getId();
        }
        return base;
    }

    /**
     * 实收明细业务键（不含归一化记录 ID）。
     * <p>
     * 增量合并用：旧行先置 SUPERSEDED 再插入新行，{@code uk_received_detail_anchor} 只约束
     * ACTIVE 行，故同组同一业务键的多笔到账用「组内同键序号」区分即可，不再依赖归一化记录 ID。
     */
    private String buildDetailBaseKey(NormalizedRecordDTO r, String period) {
        return (r.getOrderNo() != null ? r.getOrderNo() : "") + "|"
            + (r.getContractNo() != null ? r.getContractNo() : "") + "|"
            + (r.getEmployeeCode() != null ? r.getEmployeeCode() : "") + "|"
            + (r.getFeeItem() != null ? r.getFeeItem() : "") + "|"
            + (r.getRoleType() != null ? r.getRoleType() : "") + "|"
            + period;
    }

    private ReceivedContract buildContract(NormalizedRecordDTO r, String period, String sourceType, Long batchId, EmployeeMainDataDTO emp) {
        ReceivedContract c = new ReceivedContract();
        c.setOrderNo(r.getOrderNo());
        c.setContractNo(r.getContractNo());
        c.setBizType(r.getBizType());
        c.setPeriod(period);
        // business_date 直接用归一化的签约(成销)时间（LocalDateTime，已含时分秒）
        if (r.getBusinessDate() != null) {
            c.setBusinessDate(r.getBusinessDate());
        } else {
            c.setBusinessDate(java.time.LocalDateTime.now());
        }
        c.setBatchId(batchId);
        c.setSourceType(sourceType);
        // 部门取该合同首条记录员工的归属部门（部门权限过滤依赖此字段）
        c.setDeptId(emp != null ? emp.getDeptId() : null);
        c.setPropertyAddress(r.getPropertyAddress());
        c.setContractAmount(r.getOriginAmount() != null ? r.getOriginAmount() : BigDecimal.ZERO);
        c.setPeriodTotalReceived(BigDecimal.ZERO);
        c.setItemCount(0);
        return c;
    }

    private ReceivedDetail buildDetail(NormalizedRecordDTO r, Long contractId, String period, String sourceKey, Long operatorId, EmployeeMainDataDTO emp) {
        ReceivedDetail d = new ReceivedDetail();
        d.setContractId(contractId);
        // 员工主数据：employeeId + deptId 一并回填（部门权限过滤依赖 dept_id）
        if (emp != null) {
            d.setEmployeeId(emp.getEmployeeId());
            d.setDeptId(emp.getDeptId());
        }
        d.setEmployeeExternalCode(r.getEmployeeCode());
        d.setRoleType(r.getRoleType());
        d.setRoleName(r.getRoleName());
        d.setShareRatio(r.getShareRatio() != null ? r.getShareRatio() : BigDecimal.ONE);
        // PERF_REAL 口径金额：取当月实收（receivedAmount）
        BigDecimal amount = r.getReceivedAmount() != null ? r.getReceivedAmount() : BigDecimal.ZERO;
        d.setPerformanceAmount(amount);
        d.setFeeItem(r.getFeeItem());
        d.setPeriod(period);
        d.setEffectiveDate(r.getBusinessDate() != null ? r.getBusinessDate().toLocalDate() : LocalDate.now());
        d.setSourceKey(sourceKey);
        d.setSourceBatchId(r.getBatchId());
        d.setNormalizedRecordId(r.getId());
        d.setDetailStatus("ACTIVE");
        d.setOperatorId(operatorId);
        return d;
    }

    // ==================== KE_RECEIVED 新签-实收人员对齐补行（2026-10-29） ====================

    /**
     * 新签-实收人员对齐补行（实现见 {@link com.panjia.received.support.ReceivedSupplementSupport}）。
     * <p>与新签到达补建单链路共用同一实现，先导实收、后导新签时由实收审批侧再次调用。
     *
     * @return 补生成的实收明细条数
     */
    private int supplementMissingEmployees(Long batchId, String period,
                                           Map<String, ReceivedContract> contractCache,
                                           Long operatorId) {
        return supplementSupport.supplement(contractCache.values(), period, batchId, null, operatorId);
    }
}
