package com.panjia.received.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.panjia.contracts.dto.EmployeeMainDataDTO;
import com.panjia.contracts.dto.NormalizedRecordDTO;
import com.panjia.contracts.dto.PerformanceFactSummaryDTO;
import com.panjia.contracts.port.ImportNormalizedRecordQueryPort;
import com.panjia.contracts.port.ImportToReceivedPort;
import com.panjia.contracts.port.CommissionPerformanceQueryPort;
import com.panjia.contracts.port.PeopleQueryPort;
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
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
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

    private static final int PAGE_SIZE = 500;

    private final ImportNormalizedRecordQueryPort importQueryPort;
    private final ReceivedContractMapper contractMapper;
    private final ReceivedDetailMapper detailMapper;
    private final PeopleQueryPort peopleQueryPort;
    private final CommissionPerformanceQueryPort commissionPerformanceQueryPort;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ImportToReceivedResult consumeBatch(Long batchId, String period, String sourceType, Long operatorId) {
        log.info("[实收写入] 开始：batchId={}, sourceType={}, period={}", batchId, sourceType, period);
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

                // 建明细
                ReceivedDetail detail = buildDetail(record, contract.getId(), period, sourceKey, operatorId, emp);
                detailMapper.insert(detail);
                newDetails++;

                // 更新合同合计
                BigDecimal amount = detail.getPerformanceAmount();
                if (contract.getPeriodTotalReceived() == null) {
                    contract.setPeriodTotalReceived(BigDecimal.ZERO);
                }
                contract.setPeriodTotalReceived(
                    contract.getPeriodTotalReceived().add(amount != null ? amount : BigDecimal.ZERO));
                contract.setItemCount(contract.getItemCount() + 1);
            }
            pageNum++;
        } while (pageResult.getRows() != null && !pageResult.getRows().isEmpty());

        // === 阶段 3：KE_RECEIVED 新签-实收人员对齐补行（2026-10-29）===
        // 实收导入文件只有部分角色人的到账行，但新签（PERF_EXPECT）可能有更多人。
        // 对新签有但实收缺失的人补生成实收明细（金额 = 该人新签应收合计），
        // 对实收已有行的人等比例压缩金额，保证合同实收合计守恒。
        int supplementedDetails = 0;
        if ("KE_RECEIVED".equals(sourceType)) {
            supplementedDetails = supplementMissingEmployees(batchId, period, contractCache, operatorId);
        }

        // 回写合同合计（补行阶段可能修改了 itemCount）
        for (ReceivedContract c : contractCache.values()) {
            contractMapper.updateById(c);
        }

        log.info("[实收写入] 完成：batchId={}, newContracts={}, newDetails={}, skippedRefundRows={}, supplementedDetails={}",
            batchId, newContracts, newDetails, skippedRefundRows, supplementedDetails);
        return new ImportToReceivedResult(newContracts, newDetails, skippedRefundRows, supplementedDetails);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void supersedeBatches(java.util.List<Long> oldBatchIds) {
        if (oldBatchIds == null || oldBatchIds.isEmpty()) {
            return;
        }
        detailMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<ReceivedDetail>()
            .in(ReceivedDetail::getSourceBatchId, oldBatchIds)
            .eq(ReceivedDetail::getDetailStatus, DETAIL_STATUS_ACTIVE)
            .set(ReceivedDetail::getDetailStatus, "SUPERSEDED"));
        log.info("[实收写入] 重复导入冲销旧批次实收明细：oldBatchIds={}", oldBatchIds);
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
        String base = (r.getOrderNo() != null ? r.getOrderNo() : "") + "|"
            + (r.getContractNo() != null ? r.getContractNo() : "") + "|"
            + (r.getEmployeeCode() != null ? r.getEmployeeCode() : "") + "|"
            + (r.getFeeItem() != null ? r.getFeeItem() : "") + "|"
            + (r.getRoleType() != null ? r.getRoleType() : "") + "|"
            + period;
        // 同一业务键可能有多笔到账，追加归一化记录 ID 保证行级幂等唯一
        if (r.getId() != null) {
            return base + "|" + r.getId();
        }
        return base;
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
     * 新签-实收人员对齐补行：KE_RECEIVED 专用。
     * <p>
     * 场景：贝壳实收导入文件只有部分角色人的到账行，但 PERF_EXPECT 新签事实里有更多人。
     * 对新签有但实收缺失的人，补生成实收明细（金额 = 该人新签应收合计）；
     * 对实收已有行的人，等比例压缩其实收金额，保证合同实收合计守恒。
     * <p>
     * 触发条件（全部满足才处理）：
     * <ol>
     *   <li>合同在 PERF_EXPECT 中有新签事实</li>
     *   <li>实收侧去重员工集合 ⊂ 新签侧去重员工集合（真子集，即有人缺失）</li>
     *   <li>实收侧恰好只有一个人有数据（多人场景保持现状不处理）</li>
     * </ol>
     *
     * @return 补生成的实收明细条数
     */
    private int supplementMissingEmployees(Long batchId, String period,
                                           Map<String, ReceivedContract> contractCache,
                                           Long operatorId) {
        // 1. 收集所有 orderNo + contractNo，查对应的 PERF_EXPECT 新签事实（跨期间，新签可能早于实收月）
        Set<String> bizKeys = new HashSet<>();
        for (ReceivedContract c : contractCache.values()) {
            if (c.getOrderNo() != null && !c.getOrderNo().isBlank()) {
                bizKeys.add(c.getOrderNo().trim());
            }
            if (c.getContractNo() != null && !c.getContractNo().isBlank()) {
                bizKeys.add(c.getContractNo().trim());
            }
        }
        if (bizKeys.isEmpty()) {
            return 0;
        }

        List<PerformanceFactSummaryDTO> expectFacts = commissionPerformanceQueryPort
            .findActiveByBizKeys(bizKeys, "PERF_EXPECT");
        if (expectFacts == null || expectFacts.isEmpty()) {
            return 0;
        }

        // 2. 按订单号归组新签事实 → 每人应收合计 + 代表性 fact（取字段用）
        Map<String, Map<Long, BigDecimal>> expectAmountByEmp = new LinkedHashMap<>();
        Map<String, Map<Long, PerformanceFactSummaryDTO>> expectRepByEmp = new LinkedHashMap<>();
        for (PerformanceFactSummaryDTO fact : expectFacts) {
            String targetOrderNo = fact.getOrderNo();
            if (targetOrderNo == null || targetOrderNo.isBlank()) {
                targetOrderNo = findOrderNoByContract(contractCache, fact.getContractNo());
                if (targetOrderNo == null) continue;
            }
            Long empId = fact.getEmployeeId();
            if (empId == null) continue;
            BigDecimal amt = fact.getAmount() != null ? fact.getAmount() : BigDecimal.ZERO;
            expectAmountByEmp.computeIfAbsent(targetOrderNo, k -> new LinkedHashMap<>())
                .merge(empId, amt, BigDecimal::add);
            expectRepByEmp.computeIfAbsent(targetOrderNo, k -> new LinkedHashMap<>())
                .putIfAbsent(empId, fact);
        }

        // 3. 逐合同处理
        int supplementCount = 0;
        for (ReceivedContract contract : contractCache.values()) {
            String orderNo = contract.getOrderNo();
            if (orderNo == null || orderNo.isBlank()) continue;

            Map<Long, BigDecimal> expectAmounts = expectAmountByEmp.get(orderNo);
            if (expectAmounts == null || expectAmounts.isEmpty()) continue;

            // 查合同下已有的 ACTIVE 实收明细
            List<ReceivedDetail> existingDetails = detailMapper.selectList(
                new LambdaQueryWrapper<ReceivedDetail>()
                    .eq(ReceivedDetail::getContractId, contract.getId())
                    .eq(ReceivedDetail::getDetailStatus, DETAIL_STATUS_ACTIVE));
            if (existingDetails.isEmpty()) continue;

            // 实收侧去重员工 + 每人实收合计
            Map<Long, BigDecimal> realByEmpId = new LinkedHashMap<>();
            for (ReceivedDetail d : existingDetails) {
                if (d.getEmployeeId() == null) continue;
                realByEmpId.merge(d.getEmployeeId(),
                    d.getPerformanceAmount() != null ? d.getPerformanceAmount() : BigDecimal.ZERO,
                    BigDecimal::add);
            }

            Set<Long> expectEmps = expectAmounts.keySet();
            Set<Long> realEmps = realByEmpId.keySet();

            // 真子集检查：实收 ⊂ 新签 → 有人缺失
            LinkedHashSet<Long> missing = new LinkedHashSet<>(expectEmps);
            missing.removeAll(realEmps);
            if (missing.isEmpty()) continue;

            // 实收侧多人场景 → 跳过（用户确认单人场景才触发）
            if (realEmps.size() != 1) {
                log.warn("[实收补行] 实收侧有多人({})，跳过：orderNo={}", realEmps.size(), orderNo);
                continue;
            }

            log.info("[实收补行] 触发：orderNo={}, 新签人数={}, 实收人数={}, 缺失人数={}",
                orderNo, expectEmps.size(), realEmps.size(), missing.size());

            // 4. 计算缺失人应收合计
            BigDecimal sumMissingExpect = BigDecimal.ZERO;
            for (Long empId : missing) {
                BigDecimal amt = expectAmounts.getOrDefault(empId, BigDecimal.ZERO);
                sumMissingExpect = sumMissingExpect.add(amt);
            }

            // 5. 调整已有实收人的金额：等比例压缩
            BigDecimal newExistingTotal = contract.getPeriodTotalReceived().subtract(sumMissingExpect);
            Long existingEmpId = realEmps.iterator().next();
            adjustExistingEmpRows(existingDetails, existingEmpId, newExistingTotal);

            // 6. 为缺失人补生成实收明细（从 PERF_EXPECT 镜像字段）
            Map<Long, PerformanceFactSummaryDTO> repMap = expectRepByEmp.get(orderNo);
            for (Long empId : missing) {
                PerformanceFactSummaryDTO fact = repMap != null ? repMap.get(empId) : null;
                if (fact == null) continue;
                BigDecimal expectAmt = expectAmounts.getOrDefault(empId, BigDecimal.ZERO);

                ReceivedDetail newDetail = new ReceivedDetail();
                newDetail.setContractId(contract.getId());
                newDetail.setEmployeeId(empId);
                newDetail.setDeptId(fact.getDeptId());
                newDetail.setEmployeeExternalCode(fact.getEmployeeCode());
                newDetail.setRoleType(fact.getRoleType());
                newDetail.setRoleName(fact.getEmployeeName());
                newDetail.setShareRatio(fact.getShareRatio() != null ? fact.getShareRatio() : BigDecimal.ONE);
                newDetail.setPerformanceAmount(expectAmt);
                newDetail.setFeeItem(fact.getFeeItem());
                newDetail.setPeriod(period);
                newDetail.setEffectiveDate(fact.getBusinessDate() != null
                    ? fact.getBusinessDate().toLocalDate() : LocalDate.now());
                newDetail.setSourceKey(buildSupplementSourceKey(orderNo, contract.getContractNo(),
                    fact, period));
                newDetail.setSourceBatchId(batchId);
                newDetail.setNormalizedRecordId(null);
                newDetail.setDetailStatus(DETAIL_STATUS_ACTIVE);
                newDetail.setOperatorId(operatorId);
                detailMapper.insert(newDetail);
                supplementCount++;
            }

            // 7. 更新合同 itemCount（periodTotalReceived 不变：先减后加已抵消）
            contract.setItemCount(contract.getItemCount() + missing.size());

            log.info("[实收补行] 完成：orderNo={}, supplementCount={}, sumMissingExpect={}, newExistingTotal={}",
                orderNo, supplementCount, sumMissingExpect, newExistingTotal);
        }
        return supplementCount;
    }

    /**
     * 把指定员工的所有实收明细行按比例压缩，使合计 = targetAmount。
     * <p>
     * 等比例公式：每条行 new_amount = old_amount / old_total × target_amount（保留 2 位小数）。
     * old_total = 0 时（极罕见）平均分配。
     */
    private void adjustExistingEmpRows(List<ReceivedDetail> allDetails, Long employeeId, BigDecimal targetAmount) {
        List<ReceivedDetail> empRows = new ArrayList<>();
        for (ReceivedDetail d : allDetails) {
            if (employeeId.equals(d.getEmployeeId())) empRows.add(d);
        }
        if (empRows.isEmpty()) return;

        BigDecimal oldTotal = BigDecimal.ZERO;
        for (ReceivedDetail d : empRows) {
            oldTotal = oldTotal.add(d.getPerformanceAmount() != null ? d.getPerformanceAmount() : BigDecimal.ZERO);
        }

        if (oldTotal.compareTo(BigDecimal.ZERO) == 0) {
            BigDecimal perRow = targetAmount.divide(BigDecimal.valueOf(empRows.size()), 2, RoundingMode.HALF_UP);
            for (ReceivedDetail d : empRows) {
                d.setPerformanceAmount(perRow);
                detailMapper.updateById(d);
            }
        } else {
            for (ReceivedDetail d : empRows) {
                BigDecimal oldAmount = d.getPerformanceAmount() != null ? d.getPerformanceAmount() : BigDecimal.ZERO;
                BigDecimal newAmount = oldAmount.divide(oldTotal, 10, RoundingMode.HALF_UP)
                    .multiply(targetAmount).setScale(2, RoundingMode.HALF_UP);
                d.setPerformanceAmount(newAmount);
                detailMapper.updateById(d);
            }
        }
    }

    /** 新签事实没有 orderNo 时，从 contractCache 里按 contractNo 反查 orderNo。 */
    private String findOrderNoByContract(Map<String, ReceivedContract> contractCache, String contractNo) {
        if (contractNo == null || contractNo.isBlank()) return null;
        for (ReceivedContract c : contractCache.values()) {
            if (contractNo.trim().equals(c.getContractNo()) && c.getOrderNo() != null) {
                return c.getOrderNo();
            }
        }
        return null;
    }

    /**
     * 补行专用 sourceKey。格式同 {@link #buildDetailSourceKey}，追加 SUPPLEMENT 标记 + 新签 factId，
     * 避免和原始行冲突；重导时旧批次被 supersede，补行也随批次一起失效重建。
     */
    private String buildSupplementSourceKey(String orderNo, String contractNo,
                                            PerformanceFactSummaryDTO fact, String period) {
        return (orderNo != null ? orderNo : "") + "|"
            + (contractNo != null ? contractNo : "") + "|"
            + (fact.getEmployeeCode() != null ? fact.getEmployeeCode() : "") + "|"
            + (fact.getFeeItem() != null ? fact.getFeeItem() : "") + "|"
            + (fact.getRoleType() != null ? fact.getRoleType() : "") + "|"
            + period + "|SUPPLEMENT|" + fact.getFactId();
    }
}
