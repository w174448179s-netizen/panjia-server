package com.panjia.received.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.panjia.contracts.dto.NormalizedRecordDTO;
import com.panjia.contracts.port.ImportNormalizedRecordQueryPort;
import com.panjia.contracts.port.ImportToReceivedPort;
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
import java.util.LinkedHashMap;
import java.util.Map;

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

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ImportToReceivedResult consumeBatch(Long batchId, String period, String sourceType, Long operatorId) {
        log.info("[实收写入] 开始：batchId={}, sourceType={}, period={}", batchId, sourceType, period);
        int newContracts = 0;
        int newDetails = 0;
        int pageNum = 1;
        Map<String, ReceivedContract> contractCache = new LinkedHashMap<>();

        PageResult<NormalizedRecordDTO> pageResult;
        do {
            pageResult = importQueryPort.listByBatchId(batchId, pageNum, PAGE_SIZE);
            if (pageResult == null || pageResult.getRows() == null) break;

            for (NormalizedRecordDTO record : pageResult.getRows()) {
                if (!isReceivedRecord(record, sourceType)) continue;

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
                    contract = buildContract(record, period, sourceType, batchId);
                    contractMapper.insert(contract);
                    newContracts++;
                    log.info("[实收写入] 新建合同：id={}, orderNo={}, period={}",
                        contract.getId(), contract.getOrderNo(), period);
                }

                // 建明细
                ReceivedDetail detail = buildDetail(record, contract.getId(), period, sourceKey, operatorId);
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

        // 回写合同合计
        for (ReceivedContract c : contractCache.values()) {
            contractMapper.updateById(c);
        }

        log.info("[实收写入] 完成：batchId={}, newContracts={}, newDetails={}", batchId, newContracts, newDetails);
        return new ImportToReceivedResult(newContracts, newDetails);
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

    private String buildDetailSourceKey(NormalizedRecordDTO r, String period) {
        return (r.getOrderNo() != null ? r.getOrderNo() : "") + "|"
            + (r.getEmployeeCode() != null ? r.getEmployeeCode() : "") + "|"
            + period + "|"
            + (r.getBusinessDate() != null ? r.getBusinessDate() : "");
    }

    private ReceivedContract buildContract(NormalizedRecordDTO r, String period, String sourceType, Long batchId) {
        ReceivedContract c = new ReceivedContract();
        c.setOrderNo(r.getOrderNo());
        c.setContractNo(r.getContractNo());
        c.setBizType(r.getBizType());
        c.setPeriod(period);
        // business_date 用归一化的 sign_date → 或 businessDate（LocalDate → LocalDateTime）
        if (r.getBusinessDate() != null) {
            c.setBusinessDate(java.time.LocalDateTime.of(r.getBusinessDate(), java.time.LocalTime.MIN));
        } else {
            c.setBusinessDate(java.time.LocalDateTime.now());
        }
        c.setBatchId(batchId);
        c.setSourceType(sourceType);
        // 部门暂从归一化拿不到（deptFullName 是字符串，后续加 PeopleQueryPort），先留 null
        c.setPropertyAddress(r.getPropertyAddress());
        c.setContractAmount(r.getOriginAmount() != null ? r.getOriginAmount() : BigDecimal.ZERO);
        c.setPeriodTotalReceived(BigDecimal.ZERO);
        c.setItemCount(0);
        return c;
    }

    private ReceivedDetail buildDetail(NormalizedRecordDTO r, Long contractId, String period, String sourceKey, Long operatorId) {
        ReceivedDetail d = new ReceivedDetail();
        d.setContractId(contractId);
        // employeeId 暂留 null（归一化只有 employeeCode 字符串），后续加 PeopleQueryPort 查
        d.setEmployeeExternalCode(r.getEmployeeCode());
        d.setRoleType(r.getRoleType());
        d.setRoleName(r.getRoleName());
        d.setShareRatio(r.getShareRatio() != null ? r.getShareRatio() : BigDecimal.ONE);
        // PERF_REAL 口径金额：取当月实收（receivedAmount）
        BigDecimal amount = r.getReceivedAmount() != null ? r.getReceivedAmount() : BigDecimal.ZERO;
        d.setPerformanceAmount(amount);
        d.setFeeItem(r.getFeeItem());
        d.setPeriod(period);
        d.setEffectiveDate(r.getBusinessDate() != null ? r.getBusinessDate() : LocalDate.now());
        d.setSourceKey(sourceKey);
        d.setSourceBatchId(r.getBatchId());
        d.setNormalizedRecordId(r.getId());
        d.setDetailStatus("ACTIVE");
        d.setOperatorId(operatorId);
        return d;
    }
}
