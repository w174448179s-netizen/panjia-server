package com.panjia.importdomain.service;

import com.panjia.contracts.port.BatchConsumptionQueryPort;
import com.panjia.importdomain.domain.ImportBatch;
import com.panjia.importdomain.domain.ImportSourceType;
import com.panjia.importdomain.domain.NormalizedRecord;
import com.panjia.importdomain.mapper.ImportBatchMapper;
import com.panjia.importdomain.mapper.NormalizedRecordMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

/**
 * 重复批次冲销服务：同（来源类型, 归属月, 部门）仅允许一份生效（ARCHIVED）批次。
 * <p>
 * 设计依据：V2.0 §5.5 / §6.1 / ADR-IMP-004（单据逻辑失效）。
 * <p>
 * <b>维度口径（2026-10-29 收敛）</b>：新签/历史工资类来源（KE_SIGNED、HISTORY_PAYROLL）下游锚点
 * 全部是全局口径（业绩事实 source_key、实收审批单 period+order_no 均不含 dept），因此这类来源的
 * 重复批次判定<b>忽略 deptId</b>，全公司同期间只允许一份生效批次；否则两个部门分别归档同月批次会
 * 重复累加，且新签第二批会因 source_key 全局唯一被整批幂等跳过。
 * 考勤/积分等按部门组织且下游按部门消费的来源仍按 dept 维度判定。
 * KE_RECEIVED 自 2026-10-29 起改为「订单 + 归属月」粒度增量合并（见 ImportEngine.isMergeSource），
 * 不再经过本服务整批冲销，批次仅作留痕。
 * <p>
 * 部分唯一索引 uk_import_batch_type_period_dept 仅约束
 * status=ARCHIVED 且 superseded_by_batch_id IS NULL 的行；
 * 因此新批次转入 ARCHIVED 前，必须先把同组合的旧生效批次标记 SUPERSEDED，
 * 否则 UPDATE 撞唯一索引。
 * <p>
 * 两条归档路径共用本服务：
 * <ul>
 *   <li>自动归档（ImportEngine 归一化成功 → ARCHIVED）</li>
 *   <li>手动归档（ImportBatchService.archive，如待确认批次人工确认）</li>
 * </ul>
 * <p>
 * 重复导入前置校验（2026-10-29）：与「撤销导入」对称，同维度已存在生效批次时，
 * 先通过 {@link BatchConsumptionQueryPort} 反向查询下游消费状态（封账/业绩调整/实收确认）。
 * 已产生消费即拒绝本次导入，避免整批冲销把已调整、已结佣的数据打回草稿。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BatchSupersedeService {

    private final ImportBatchMapper batchMapper;

    /** 反向端口：导入域不直接依赖业绩域，缺失时降级为拒绝（宁可不导也不误冲） */
    private final ObjectProvider<BatchConsumptionQueryPort> consumptionQueryPortProvider;

    private final NormalizedRecordMapper normalizedRecordMapper;

    /**
     * 查同（类型, 期间, 部门）的旧生效批次（ARCHIVED 且未被冲销）。
     * <p>
     * 归档前调用时新批次尚未 ARCHIVED，无需排除自身；仅当传入已归档的新批次
     * （手动归档重入场景）才追加 id 排除，避免 {@code id <> NULL} 恒不命中。
     *
     * @return 旧生效批次；不存在时返回 null
     */
    public ImportBatch findActiveArchivedBatch(ImportSourceType sourceType, String period, Long deptId, Long selfId) {
        LambdaQueryWrapper<ImportBatch> wrapper = new LambdaQueryWrapper<ImportBatch>()
            .eq(ImportBatch::getSourceType, sourceType)
            .eq(ImportBatch::getPeriod, period)
            .eq(ImportBatch::getStatus, com.panjia.importdomain.domain.ImportBatchStatus.ARCHIVED)
            .isNull(ImportBatch::getSupersededByBatchId);
        // 业绩类来源下游锚点为全局口径，重复批次判定忽略部门（详见类注释）
        if (!isCompanyScoped(sourceType)) {
            wrapper.eq(ImportBatch::getDeptId, deptId);
        }
        if (selfId != null) {
            wrapper.ne(ImportBatch::getId, selfId);
        }
        return batchMapper.selectOne(wrapper.last("LIMIT 1"));
    }

    /**
     * 是否为「全公司一份」来源：下游事实/单据锚点不含 dept，重复导入必须在公司范围内冲销。
     * <p>KE_RECEIVED 自 2026-10-29 改为订单/月粒度增量合并，不再走整批冲销，故不在此列；
     * POINTS 允许同月多份并存；ATTENDANCE/OTHERS 按部门各自归档。
     */
    public static boolean isCompanyScoped(ImportSourceType sourceType) {
        return sourceType == ImportSourceType.KE_SIGNED
            || sourceType == ImportSourceType.HISTORY_PAYROLL;
    }

    /**
     * 重复导入前置校验（三参版本，供导入入口在解析/落库前调用）。
     *
     * @throws ServiceException 下游已消费或校验端口缺失（保守拒绝）
     */
    public void assertReimportAllowed(ImportSourceType sourceType, String period, Long deptId) {
        doAssertReimportAllowed(findActiveArchivedBatch(sourceType, period, deptId, null));
    }

    /**
     * 重复导入前置校验：同维度已存在生效批次时，校验其下游是否已消费。
     * <p>
     * 与 {@code ImportBatchServiceImpl#revoke} 的 {@code checkRevocable} 口径一致：
     * 已封账、已审批的业绩调整、已审批的实收确认单 → 拒绝重复导入。
     * 否则重复导入会整批冲销旧批次，连带把已调整/已结佣的申请单退回草稿。
     *
     * @throws ServiceException 下游已消费或校验端口缺失（保守拒绝）
     */
    public void assertReimportAllowed(ImportBatch newBatch) {
        doAssertReimportAllowed(findActiveArchivedBatch(
            newBatch.getSourceType(), newBatch.getPeriod(), newBatch.getDeptId(), newBatch.getId()));
    }

    private void doAssertReimportAllowed(ImportBatch old) {
        if (old == null) {
            return;
        }
        BatchConsumptionQueryPort port = consumptionQueryPortProvider.getIfAvailable();
        if (port == null) {
            throw new ServiceException("归属月 " + old.getPeriod() + " 已存在生效批次（"
                + old.getBatchNo() + "），重复导入需校验下游消费状态，但校验组件不可用，已拒绝本次导入");
        }
        BatchConsumptionQueryPort.RevokeCheckResult result =
            port.checkRevocable(old.getId(), old.getPeriod());
        if (!result.isRevocable()) {
            throw new ServiceException("归属月 " + old.getPeriod() + " 已存在生效批次（"
                + old.getBatchNo() + "）且已被下游使用：" + result.getReason()
                + "；禁止重复导入，请通过业绩调整流程修改");
        }
        log.info("重复导入校验通过（下游未消费）: oldBatchId={}, sourceType={}, period={}, deptId={}",
            old.getId(), old.getSourceType(), old.getPeriod(), old.getDeptId());
    }

    /**
     * 判断两个批次的归一化内容是否完全一致（行级指纹逐行比对）。
     * <p>
     * 用于重复导入幂等：内容一致时跳过本次导入（不冲销、不归档、不发事件），
     * 避免同一份文件误传两次产生无意义的 REVERSED 历史行与冲销事件。
     * 任一侧无归一化记录时返回 false，交回正常替换流程，不误判为跳过。
     *
     * @return true 表示内容完全一致
     */
    public boolean isContentIdentical(Long newBatchId, Long oldBatchId) {
        List<String> newFingerprints = fingerprints(newBatchId);
        List<String> oldFingerprints = fingerprints(oldBatchId);
        if (newFingerprints.isEmpty() || oldFingerprints.isEmpty()
            || newFingerprints.size() != oldFingerprints.size()) {
            return false;
        }
        return newFingerprints.equals(oldFingerprints);
    }

    /** 按批次取归一化记录并按指纹排序（排序后逐行比对，避免行序差异误判） */
    private List<String> fingerprints(Long batchId) {
        return normalizedRecordMapper.selectList(new LambdaQueryWrapper<NormalizedRecord>()
                .eq(NormalizedRecord::getBatchId, batchId))
            .stream()
            .map(BatchSupersedeService::fingerprint)
            .sorted()
            .toList();
    }

    /**
     * 单行指纹：业务键 + 角色 + 金额 + 原始描述字段。
     * 排除 id/batchId/createTime/validation* 等技术字段，避免同一内容因批次不同而误判。
     */
    private static String fingerprint(NormalizedRecord r) {
        return String.join("|",
            text(r.getRecordType()),
            text(r.getSourceKey()),
            text(r.getOrderNo()),
            text(r.getContractNo()),
            text(r.getRoleType()),
            text(r.getRoleName()),
            text(r.getEmployeeExternalCode()),
            amount(r.getReceivableAmount()),
            amount(r.getReceivedAmount()),
            amount(r.getTotalReceivableAmount()),
            amount(r.getTotalReceivedAmount()),
            amount(r.getShareRatio()),
            text(r.getBizType()),
            text(r.getSignDate()),
            text(r.getFeeItem()),
            text(r.getPropertyAddress()),
            text(r.getExtraJson()));
    }

    private static String text(Object value) {
        return value == null ? "" : value.toString();
    }

    private static String amount(BigDecimal value) {
        return value == null ? "" : value.stripTrailingZeros().toPlainString();
    }

    /**
     * 若存在同（类型, 期间, 部门）的旧生效批次（ARCHIVED 且未被冲销），
     * 将其标记为被 newBatch 冲销（SUPERSEDED）。
     * <p>
     * 冲销前再次执行下游消费校验：即使入口已校验，归一化期间仍可能有新的调整/实收
     * 审批落库，此处为事务内的最后一道防线。
     *
     * @return 被冲销的旧批次 ID；无旧批次时返回 null
     * @throws ServiceException 旧批次已被下游消费，禁止冲销
     */
    public Long supersedeOldArchivedBatch(ImportBatch newBatch) {
        // 最后一道防线：事务内二次校验，防止归一化期间新产生的调整被误冲
        assertReimportAllowed(newBatch);
        ImportBatch old = findActiveArchivedBatch(
            newBatch.getSourceType(), newBatch.getPeriod(), newBatch.getDeptId(), newBatch.getId());
        if (old == null) {
            return null;
        }
        log.info("重复导入归档标记 SUPERSEDED: oldBatchId={} -> newBatchId={} (sourceType={}, period={}, deptId={})",
            old.getId(), newBatch.getId(), newBatch.getSourceType(), newBatch.getPeriod(), newBatch.getDeptId());
        batchMapper.markSuperseded(old.getId(), newBatch.getId());
        return old.getId();
    }
}
