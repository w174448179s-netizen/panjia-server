package com.panjia.importdomain.adapter;

import com.panjia.contracts.dto.NormalizedRecordDTO;
import com.panjia.contracts.port.ImportNormalizedRecordQueryPort;
import com.panjia.importdomain.domain.ImportBatch;
import com.panjia.importdomain.domain.NormalizedRecord;
import com.panjia.importdomain.domain.NormalizedRecordType;
import com.panjia.importdomain.domain.raw.RawSigned;
import com.panjia.importdomain.mapper.ImportBatchMapper;
import com.panjia.importdomain.mapper.NormalizedRecordMapper;
import com.panjia.importdomain.mapper.RawSignedMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.domain.PageResult;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;

/**
 * 归一化记录查询适配器（panjia-import 模块实现 {@link ImportNormalizedRecordQueryPort}）。
 * <p>
 * 设计说明：ImportNormalizedRecordQueryPort 是跨域端口（定义在 panjia-contracts），
 * 实现方在数据归属域（panjia-import）。依赖方向 performance → contracts ← import，
 * 业绩域完全不知道 import 域的 mapper / entity，仅依赖 contracts 端口。
 * <p>
 * 查询过滤：仅 {@code status='ARCHIVED' AND superseded_by_batch_id IS NULL} 的批次
 * 归一化记录对业绩域可见（避免新旧业绩并存）。该过滤由 NormalizedRecordMapper SQL 内置。
 * <p>
 * DTO 映射：NormalizedRecord → NormalizedRecordDTO 仅做字段拷贝 + sourceType 从 ImportBatch 注入。
 * V2.0 业务日期（签约日）、员工姓名、部门全路径暂未填充，留 null。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ImportQueryAdapter implements ImportNormalizedRecordQueryPort {

    private final NormalizedRecordMapper normalizedRecordMapper;
    private final ImportBatchMapper importBatchMapper;
    private final RawSignedMapper rawSignedMapper;

    @Override
    public PageResult<NormalizedRecordDTO> listByBatchId(Long batchId, int pageNum, int pageSize) {
        if (batchId == null) {
            return PageResult.build(Collections.emptyList(), 0L);
        }
        // 端口契约层（panjia-contracts）只承诺基础 int 入参；这里做防御性兜底，避免 mapper 收到非法分页值。
        if (pageNum <= 0) pageNum = ImportNormalizedRecordQueryPort.DEFAULT_PAGE_NUM;
        if (pageSize <= 0) pageSize = ImportNormalizedRecordQueryPort.DEFAULT_PAGE_SIZE;
        int offset = (pageNum - 1) * pageSize;

        long total = normalizedRecordMapper.countByBatchIdActive(batchId);
        if (total == 0) {
            return PageResult.build(Collections.emptyList(), 0L);
        }

        // 先查一次 ImportBatch 拿 sourceType（避免每行再查一次）
        ImportBatch batch = importBatchMapper.selectById(batchId);
        String sourceType = (batch == null || batch.getSourceType() == null)
            ? null : batch.getSourceType().getCode();

        List<NormalizedRecord> records = normalizedRecordMapper.selectPageByBatchId(batchId, offset, pageSize);
        List<NormalizedRecordDTO> rows = records.stream()
            .map(r -> toDTO(r, sourceType))
            .toList();

        return PageResult.build(rows, total);
    }

    @Override
    public long countByBatchId(Long batchId) {
        if (batchId == null) {
            return 0L;
        }
        return normalizedRecordMapper.countByBatchIdActive(batchId);
    }

    @Override
    public String getRawJsonByRecordId(Long recordId) {
        if (recordId == null) {
            return null;
        }
        NormalizedRecord record = normalizedRecordMapper.selectById(recordId);
        if (record == null || record.getRawDataId() == null) {
            return null;
        }
        // 业绩记录统一路由到 SIGNED 原始行表（贝壳业绩明细表，唯一业绩来源）
        NormalizedRecordType type = record.getRecordType();
        if (type == NormalizedRecordType.SIGNED) {
            RawSigned raw = rawSignedMapper.selectById(record.getRawDataId());
            return raw == null ? null : raw.getRawJson();
        }
        return null;
    }

    /**
     * V2.0 简化映射：仅映射已有字段；employeeName / deptFullName 留 null，
     * 等待 V2.1 / PeopleSnapshotAdapter 接入后补齐。
     * <p>
     * businessDate 用归属月初填充：月度归集的导入业绩业务日期精确到月已足够，
     * 且 pj_perf_fact.business_date / effective_date 为 NOT NULL，null 会让
     * 消费侧事实 INSERT 直接炸掉（derivePeriod(月初) == period，语义自洽）。
     */
    private NormalizedRecordDTO toDTO(NormalizedRecord r, String sourceType) {
        NormalizedRecordDTO dto = new NormalizedRecordDTO();
        dto.setId(r.getId());
        dto.setBatchId(r.getBatchId());
        dto.setSourceType(sourceType);
        dto.setBusinessDate(periodStartDate(r.getPeriod()));
        dto.setPeriod(r.getPeriod());
        dto.setEmployeeCode(r.getEmployeeExternalCode());
        dto.setEmployeeName(null);  // TODO: 待 PeopleSnapshotAdapter 接入后填充
        dto.setDeptFullName(null);  // TODO: 待 PeopleSnapshotAdapter 接入后填充
        dto.setBizType(r.getBizType());
        dto.setSourceKey(r.getSourceKey());
        dto.setRecordType(r.getRecordType() == null ? null : r.getRecordType().name());
        // ★ 双口径金额同时透传（V4.2 算薪对齐 / C-12 锚点）：
        //  receivableAmount 当月应收 → PERF_EXPECT（新签业绩，样本 206,274.04 / 273 非零行）
        //  receivedAmount   当月实收 → PERF_REAL（结佣计薪业绩，样本 192,556.89 / 266 非零行）
        //  SIGNED 行两列并存，业绩引擎对其双发两条事实；originAmount 保留单口径默认值兼容旧消费方
        dto.setReceivableAmount(r.getReceivableAmount());
        dto.setReceivedAmount(r.getReceivedAmount());
        dto.setOriginAmount(resolveOriginAmount(r));
        dto.setShareRatio(r.getShareRatio());
        dto.setRoleType(r.getRoleType());
        dto.setExtJson(r.getExtraJson());
        return dto;
    }

    /**
     * 解析业绩原值：贝壳业绩明细表（SIGNED）同一行同时携应收/实收，
     * originAmount 保留<b>实收</b>口径作为默认值，兼容旧消费方；
     * 具体口径金额由业绩引擎按 receivableAmount / receivedAmount 双发。
     * 其余类型（考勤/积分/手工）当前不产生金额型业绩事实，同样兜底取实收列。
     */
    private java.math.BigDecimal resolveOriginAmount(NormalizedRecord r) {
        return r.getReceivedAmount();
    }

    /** "YYYY-MM" → 该月 1 号；period 为空或非法返回 null（消费侧 fail fast） */
    private java.time.LocalDate periodStartDate(String period) {
        if (period == null || period.length() != 7) {
            return null;
        }
        try {
            return java.time.LocalDate.of(
                Integer.parseInt(period.substring(0, 4)),
                Integer.parseInt(period.substring(5, 7)), 1);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}