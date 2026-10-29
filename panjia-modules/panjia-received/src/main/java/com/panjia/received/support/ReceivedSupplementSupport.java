package com.panjia.received.support;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.panjia.contracts.dto.PerformanceFactSummaryDTO;
import com.panjia.contracts.port.CommissionPerformanceQueryPort;
import com.panjia.received.domain.ReceivedContract;
import com.panjia.received.domain.ReceivedDetail;
import com.panjia.received.mapper.ReceivedDetailMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 新签-实收人员对齐补行（原 ImportToReceivedServiceImpl 私有逻辑抽取，2026-10-29）。
 * <p>
 * 两条链路共用：
 * <ol>
 *   <li>贝壳实收导入归档（{@code ImportToReceivedServiceImpl.consumeBatch}）；</li>
 *   <li>新签到达后为「已到账、未建单」的实收补建审批单（{@code ReceivedApplyServiceImpl.resolvePendingAfterNewSign}）
 *        —— 先导实收、后导新签时，导入当时没有新签无法补行，新签到达必须重做一次，
 *       否则人员不一致会被强制转人工审批。</li>
 * </ol>
 * 补行口径（业务定稿）：实收侧恰好 1 人、且实收人员真子集于新签人员时，
 * 缺失人按其新签应收补行；已有实收人的合计 = 实收总额 - 缺失人应收合计（合同实收合计守恒）。
 * <p>补录来源可追溯：新签触发写入 {@code supplementOriginBatchId = 新签批次}，
 * 撤销该新签批次时删除补录行、还原被压缩的原行（见 {@code ReceivedApplyPort#revokeSupplements}）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReceivedSupplementSupport {

    /** 明细有效状态 */
    private static final String DETAIL_STATUS_ACTIVE = "ACTIVE";
    /** 明细被替换后置为失效 */
    private static final String DETAIL_STATUS_SUPERSEDED = "SUPERSEDED";
    /** 结佣口径：新签应收 */
    private static final String FACT_TYPE_EXPECT = "PERF_EXPECT";

    private final ReceivedDetailMapper detailMapper;
    private final CommissionPerformanceQueryPort commissionPerformanceQueryPort;

    /**
     * 按新签应收补齐实收缺失人员行（合同/订单实收合计守恒）。
     * <p>补录口径（业务定稿，<b>不是按总额比例分摊</b>）：
     * <ul>
     *   <li>没有实收数据的缺失人：实收业绩 = 其新签应收金额；</li>
     *   <li>有实收数据的人：实收业绩 = 订单实收总额 - 其他所有人（缺失人）应收合计；</li>
     *   <li>多人同时有实收数据时，剩余金额按其原到账金额比例拆到人（人员合计始终满足上式）。</li>
     * </ul>
     *
     * @param contracts       参与补行的实收合同（按订单/期间维度）
     * @param period          触发补行的归属期间
     * @param receivedBatchId 实收导入批次 ID（导入链路触发；补录行随该批次撤销删除）
     * @param signBatchId     新签导入批次 ID（新签到达补建单触发；撤销该新签批次时补录行/压缩还原）
     * @param operatorId      操作人 ID
     * @return 补生成的明细条数
     */
    public int supplement(Collection<ReceivedContract> contracts, String period,
                          Long receivedBatchId, Long signBatchId, Long operatorId) {
        if (contracts == null || contracts.isEmpty()) {
            return 0;
        }
        // 1. 收集所有 orderNo + contractNo，查对应的 PERF_EXPECT 新签事实（跨期间，新签可能早于实收月）
        Set<String> bizKeys = new HashSet<>();
        for (ReceivedContract c : contracts) {
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
            .findActiveByBizKeys(bizKeys, FACT_TYPE_EXPECT);
        if (expectFacts == null || expectFacts.isEmpty()) {
            return 0;
        }

        // 2. 按订单号归组新签事实 → 每人应收合计 + 代表性事实（取字段用）
        Map<String, Map<Long, BigDecimal>> expectAmountByEmp = new LinkedHashMap<>();
        Map<String, Map<Long, PerformanceFactSummaryDTO>> expectRepByEmp = new LinkedHashMap<>();
        for (PerformanceFactSummaryDTO fact : expectFacts) {
            String targetOrderNo = fact.getOrderNo();
            if (targetOrderNo == null || targetOrderNo.isBlank()) {
                targetOrderNo = findOrderNoByContract(contracts, fact.getContractNo());
                if (targetOrderNo == null) {
                    continue;
                }
            }
            Long empId = fact.getEmployeeId();
            if (empId == null) {
                continue;
            }
            BigDecimal amt = fact.getAmount() != null ? fact.getAmount() : BigDecimal.ZERO;
            expectAmountByEmp.computeIfAbsent(targetOrderNo, k -> new LinkedHashMap<>())
                .merge(empId, amt, BigDecimal::add);
            expectRepByEmp.computeIfAbsent(targetOrderNo, k -> new LinkedHashMap<>())
                .putIfAbsent(empId, fact);
        }

        // 3. 逐合同处理
        int supplementCount = 0;
        for (ReceivedContract contract : contracts) {
            String orderNo = contract.getOrderNo();
            if (orderNo == null || orderNo.isBlank()) {
                continue;
            }
            Map<Long, BigDecimal> expectAmounts = expectAmountByEmp.get(orderNo);
            if (expectAmounts == null || expectAmounts.isEmpty()) {
                continue;
            }

            // 合同下已有的 ACTIVE 实收明细
            List<ReceivedDetail> existingDetails = detailMapper.selectList(
                new LambdaQueryWrapper<ReceivedDetail>()
                    .eq(ReceivedDetail::getContractId, contract.getId())
                    .eq(ReceivedDetail::getDetailStatus, DETAIL_STATUS_ACTIVE));
            if (existingDetails.isEmpty()) {
                continue;
            }
            // 已绑审批单的行不能改金额（审批口径已固化），整合同跳过
            boolean anyBound = existingDetails.stream().anyMatch(d -> d.getReceivedApplyId() != null);
            if (anyBound) {
                log.info("[实收补行] 合同明细已绑定审批单，跳过改金额：orderNo={}, contractId={}",
                    orderNo, contract.getId());
                continue;
            }

            // 实收侧去重员工 + 每人实收合计
            Map<Long, BigDecimal> realByEmpId = new LinkedHashMap<>();
            BigDecimal realTotal = BigDecimal.ZERO;
            for (ReceivedDetail d : existingDetails) {
                BigDecimal amt = d.getPerformanceAmount() != null ? d.getPerformanceAmount() : BigDecimal.ZERO;
                realTotal = realTotal.add(amt);
                if (d.getEmployeeId() == null) {
                    continue;
                }
                realByEmpId.merge(d.getEmployeeId(), amt, BigDecimal::add);
            }

            Set<Long> expectEmps = expectAmounts.keySet();
            Set<Long> realEmps = realByEmpId.keySet();

            // 真子集检查：实收 ⊂ 新签 → 有人缺失
            LinkedHashSet<Long> missing = new LinkedHashSet<>(expectEmps);
            missing.removeAll(realEmps);
            if (missing.isEmpty()) {
                continue;
            }
            // 实收侧多人场景 → 跳过（仅单人场景补行）
            if (realEmps.size() != 1) {
                log.warn("[实收补行] 实收侧有多人({})，跳过：orderNo={}", realEmps.size(), orderNo);
                continue;
            }

            log.info("[实收补行] 触发：orderNo={}, 新签人数={}, 实收人数={}, 缺失人数={}",
                orderNo, expectEmps.size(), realEmps.size(), missing.size());

            // 4. 计算缺失人应收合计
            BigDecimal sumMissingExpect = BigDecimal.ZERO;
            for (Long empId : missing) {
                sumMissingExpect = sumMissingExpect.add(
                    expectAmounts.getOrDefault(empId, BigDecimal.ZERO));
            }

            // 5. 有实收数据的人：实收业绩 = 实收总额 - 缺失人应收合计（合计守恒）
            BigDecimal newExistingTotal = realTotal.subtract(sumMissingExpect);
            if (newExistingTotal.signum() <= 0) {
                // 部分到账（到账 ≤ 缺失人应收）不做压缩：否则已有实收人会被压成负数。
                // 这类订单金额本就不足，交人工审批即可，不必强行对齐人员
                log.info("[实收补行] 到账不足以拆分缺失人份额，跳过补行：orderNo={}, real={}, missingExpect={}",
                    orderNo, realTotal, sumMissingExpect);
                continue;
            }
            Long existingEmpId = realEmps.iterator().next();
            supplementCount += compressExistingEmpRows(existingDetails, existingEmpId, newExistingTotal,
                receivedBatchId, signBatchId, operatorId);

            // 6. 为缺失人补生成实收明细（从 PERF_EXPECT 镜像字段）
            Map<Long, PerformanceFactSummaryDTO> repMap = expectRepByEmp.get(orderNo);
            for (Long empId : missing) {
                PerformanceFactSummaryDTO fact = repMap != null ? repMap.get(empId) : null;
                if (fact == null) {
                    continue;
                }
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
                // 归属月跟合同走（新签到达触发补行时会传入跨期间的全部合同，不能统一用触发月）
                String rowPeriod = contract.getPeriod() != null && !contract.getPeriod().isBlank()
                    ? contract.getPeriod() : period;
                newDetail.setPeriod(rowPeriod);
                newDetail.setEffectiveDate(fact.getBusinessDate() != null
                    ? fact.getBusinessDate().toLocalDate() : LocalDate.now());
                newDetail.setSourceKey(buildSupplementSourceKey(orderNo, contract.getContractNo(),
                    fact, rowPeriod));
                newDetail.setSourceBatchId(receivedBatchId);
                // 新签触发补录的行记来源新签批次：撤销该新签批次时按此删除，避免残留与新文件叠加
                newDetail.setSupplementOriginBatchId(signBatchId);
                newDetail.setNormalizedRecordId(null);
                newDetail.setDetailStatus(DETAIL_STATUS_ACTIVE);
                newDetail.setOperatorId(operatorId);
                detailMapper.insert(newDetail);
                supplementCount++;
            }

            log.info("[实收补行] 完成：orderNo={}, sumMissingExpect={}, newExistingTotal={}",
                orderNo, sumMissingExpect, newExistingTotal);
        }
        return supplementCount;
    }

    /**
     * 把指定员工的实收明细行压缩到 targetAmount。
     * <p><b>新签触发（signBatchId 非空）</b>：旧行置 SUPERSEDED 并记 {@code supersededByBatchId=signBatchId}，
     * 再插入压缩后的新行（{@code supplementOriginBatchId=signBatchId}）。撤销该新签批次时
     * 删除补录新行、把旧行恢复原金额 → 实收回到补录前口径，重导实收不会叠加。
     * <p><b>实收导入触发（signBatchId 为空）</b>：原地改金额。这些行本身属于本实收批次，
     * 批次撤销时整体删除、上一批明细恢复，因此无需另存旧行。
     * <p>等比例公式：new_amount = 原行金额 / 原合计 × targetAmount（保留 2 位小数）。
     *
     * @return 新插入的压缩行条数（原地更新返回 0）
     */
    private int compressExistingEmpRows(List<ReceivedDetail> allDetails, Long employeeId,
                                        BigDecimal targetAmount, Long receivedBatchId,
                                        Long signBatchId, Long operatorId) {
        List<ReceivedDetail> empRows = new ArrayList<>();
        for (ReceivedDetail d : allDetails) {
            if (employeeId.equals(d.getEmployeeId())) {
                empRows.add(d);
            }
        }
        if (empRows.isEmpty()) {
            return 0;
        }
        BigDecimal oldTotal = BigDecimal.ZERO;
        for (ReceivedDetail d : empRows) {
            oldTotal = oldTotal.add(d.getPerformanceAmount() != null ? d.getPerformanceAmount() : BigDecimal.ZERO);
        }
        int inserted = 0;
        for (ReceivedDetail d : empRows) {
            BigDecimal oldAmount = d.getPerformanceAmount() != null ? d.getPerformanceAmount() : BigDecimal.ZERO;
            BigDecimal newAmount = oldTotal.compareTo(BigDecimal.ZERO) == 0
                ? targetAmount.divide(BigDecimal.valueOf(empRows.size()), 2, RoundingMode.HALF_UP)
                : oldAmount.divide(oldTotal, 10, RoundingMode.HALF_UP)
                    .multiply(targetAmount).setScale(2, RoundingMode.HALF_UP);

            if (signBatchId == null) {
                // 实收导入链路：原地更新（随本实收批次撤销整体删除）
                d.setPerformanceAmount(newAmount);
                detailMapper.updateById(d);
                continue;
            }

            // 新签触发链路：留旧行（可还原）+ 插新行（可删除）
            d.setDetailStatus(DETAIL_STATUS_SUPERSEDED);
            d.setSupersededByBatchId(signBatchId);
            detailMapper.updateById(d);

            ReceivedDetail copy = new ReceivedDetail();
            copy.setContractId(d.getContractId());
            copy.setEmployeeId(d.getEmployeeId());
            copy.setDeptId(d.getDeptId());
            copy.setEmployeeExternalCode(d.getEmployeeExternalCode());
            copy.setRoleType(d.getRoleType());
            copy.setRoleName(d.getRoleName());
            copy.setShareRatio(d.getShareRatio());
            copy.setPerformanceAmount(newAmount);
            copy.setFeeItem(d.getFeeItem());
            copy.setPeriod(d.getPeriod());
            copy.setEffectiveDate(d.getEffectiveDate());
            copy.setExpireDate(d.getExpireDate());
            copy.setSourceKey(d.getSourceKey());
            copy.setSourceBatchId(d.getSourceBatchId() != null ? d.getSourceBatchId() : receivedBatchId);
            copy.setNormalizedRecordId(d.getNormalizedRecordId());
            copy.setSupplementOriginBatchId(signBatchId);
            copy.setDetailStatus(DETAIL_STATUS_ACTIVE);
            copy.setOperatorId(operatorId != null ? operatorId : d.getOperatorId());
            detailMapper.insert(copy);
            inserted++;
        }
        return inserted;
    }

    /** 新签事实没有 orderNo 时，按 contractNo 反查 orderNo。 */
    private String findOrderNoByContract(Collection<ReceivedContract> contracts, String contractNo) {
        if (contractNo == null || contractNo.isBlank()) {
            return null;
        }
        for (ReceivedContract c : contracts) {
            if (contractNo.trim().equals(c.getContractNo()) && c.getOrderNo() != null) {
                return c.getOrderNo();
            }
        }
        return null;
    }

    /**
     * 补行专用 sourceKey。格式同 {@code ImportToReceivedServiceImpl.buildDetailSourceKey}，
     * 追加 SUPPLEMENT 标记 + 新签 factId，避免和原始行冲突。
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
