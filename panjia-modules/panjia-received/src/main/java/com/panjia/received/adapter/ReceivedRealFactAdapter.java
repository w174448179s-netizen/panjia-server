package com.panjia.received.adapter;

import com.panjia.contracts.dto.HistoryRealFactDTO;
import com.panjia.contracts.dto.PerformanceContractSummaryDTO;
import com.panjia.contracts.dto.PerformanceFactSummaryDTO;
import com.panjia.contracts.event.EventPort;
import com.panjia.contracts.event.PerformanceFactReversedEvent;
import com.panjia.contracts.port.ReceivedRealFactPort;
import com.panjia.performance.domain.ReceivedApply;
import com.panjia.performance.domain.ReceivedApplyStatus;
import com.panjia.performance.mapper.ReceivedApplyMapper;
import com.panjia.performance.util.MoneyUtil;
import com.panjia.received.domain.ReceivedContract;
import com.panjia.received.domain.ReceivedDetail;
import com.panjia.received.mapper.ReceivedContractMapper;
import com.panjia.received.mapper.ReceivedDetailMapper;
import com.panjia.received.mapper.ReceivedRealFactMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 实收「事实」跨域端口实现（PERF_REAL 拆表后的唯一读写落地处）。
 * <p>
 * 底表 {@code pj_received_detail(rd) + pj_received_contract(rc)}，
 * 经 {@link ReceivedRealFactMapper} 的 JOIN SQL 输出 contracts 层 DTO；
 * 写操作复刻 pj_perf_fact 时代的 supersede 状态机：
 * 旧 ACTIVE 行置 REVERSED（reversal_type=SUPERSEDE/MANUAL_ADJUST），
 * 再插同 source_key 的新 ACTIVE 行（部分唯一索引 uk_received_detail_anchor 天然支持），
 * 并发 {@link PerformanceFactReversedEvent} 让结佣域按分治表联动。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReceivedRealFactAdapter implements ReceivedRealFactPort {

    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final String STATUS_REVERSED = "REVERSED";
    /** ReversedReason.SUPERSEDE（替换冲销） */
    private static final String REASON_SUPERSEDE = "SUPERSEDE";

    private final ReceivedRealFactMapper realMapper;
    private final ReceivedDetailMapper detailMapper;
    private final ReceivedContractMapper contractMapper;
    private final ReceivedApplyMapper applyMapper;
    private final EventPort eventPort;

    // ==================== 读 ====================

    @Override
    public List<PerformanceFactSummaryDTO> findActiveByDept(String period, Long deptId) {
        if (period == null || period.isBlank()) {
            return Collections.emptyList();
        }
        return realMapper.selectActiveByDept(period, deptId);
    }

    @Override
    public List<PerformanceFactSummaryDTO> findActiveByEmployee(String period, Long employeeId) {
        if (period == null || period.isBlank() || employeeId == null) {
            return Collections.emptyList();
        }
        return realMapper.selectActiveByEmployee(period, employeeId);
    }

    @Override
    public List<PerformanceFactSummaryDTO> findActiveByContract(String period, String orderNo, String contractNo) {
        // period 可空：空时查该合同全部期间（结佣发起月与实收月解耦）
        // 无合同号业务（家装荐客等）以订单号为业务锚点：合同号为空时退用订单号作匹配键
        String matchKey = (contractNo == null || contractNo.isBlank()) ? orderNo : contractNo;
        if (matchKey == null || matchKey.isBlank()) {
            return Collections.emptyList();
        }
        List<PerformanceFactSummaryDTO> rows = realMapper.selectActiveByContract(period, orderNo, matchKey);
        String on = orderNo == null ? null : orderNo.trim();
        String cn = contractNo == null ? null : contractNo.trim();
        if (!rows.isEmpty() || on == null || on.isEmpty() || cn == null || cn.isEmpty() || on.equals(cn)) {
            return rows;
        }
        // 0 命中退化：贝壳新签 order_no 误填合同号、虚拟壳重键后，调整单存档订单号可能已失效。
        // 仅单订单合同允许按合同号退化，多订单保持 0 行防串单（countDistinctOrderNos 已排除纯虚拟壳）
        if (contractMapper.countDistinctOrderNos(cn) == 1L) {
            return realMapper.selectActiveByContract(period, null, cn);
        }
        return rows;
    }

    @Override
    public List<PerformanceFactSummaryDTO> findActiveByBizKeys(Collection<String> bizKeys) {
        if (bizKeys == null || bizKeys.isEmpty()) {
            return Collections.emptyList();
        }
        return realMapper.selectActiveByBizKeys(bizKeys, bizKeys);
    }

    @Override
    public List<PerformanceFactSummaryDTO> findActiveByIds(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return Collections.emptyList();
        }
        return realMapper.selectActiveByIds(ids);
    }

    @Override
    public PerformanceFactSummaryDTO getById(Long id) {
        if (id == null) {
            return null;
        }
        return realMapper.selectByIdAnyStatus(id);
    }

    @Override
    public List<PerformanceContractSummaryDTO> listContractSummaries(String period, Long deptId, Long employeeId, String keyword) {
        // period 可空：结佣列表仅录合同号不选期间时跨期汇总（mapper 内期间条件化）
        return realMapper.selectContractSummaries(period, deptId, employeeId, keyword);
    }

    @Override
    public long countDistinctEmployeesByKeys(String period, Collection<String> bizKeys) {
        // period 可空：跨期汇总时统计全部期间去重员工数（mapper 内期间条件化）
        if (bizKeys == null || bizKeys.isEmpty()) {
            return 0L;
        }
        return realMapper.selectDistinctEmployeeCountByKeys(period, bizKeys);
    }

    @Override
    public List<PerformanceContractSummaryDTO> listApprovedApplies(Long deptId, Long employeeId, String keyword, String bizType,
                                                                   String refundFilter) {
        return realMapper.selectApprovedApplies(deptId, employeeId, keyword, bizType, refundFilter);
    }

    @Override
    public Map<String, BigDecimal> sumOriginalAmountsByKeys(String period, Collection<String> bizKeys) {
        if (period == null || period.isBlank() || bizKeys == null || bizKeys.isEmpty()) {
            return Collections.emptyMap();
        }
        List<Map<String, Object>> rows = realMapper.selectOriginalAmountsByKeys(period, bizKeys);
        Map<String, BigDecimal> result = new HashMap<>();
        for (Map<String, Object> row : rows) {
            Object key = row.get("bizKey");
            Object amount = row.get("originalAmount");
            if (key != null && amount instanceof BigDecimal bd) {
                result.put(String.valueOf(key), bd);
            }
        }
        return result;
    }

    /**
     * 「实收是否已调整」判定：业务键 ACTIVE 明细的 source_key 链存在 REVERSED 行即 true；
     * 不限归属期间。供结佣详情展示「原值 → 调整后值」。
     */
    @Override
    public boolean hasReversedChain(Collection<String> bizKeys) {
        if (bizKeys == null || bizKeys.isEmpty()) {
            return false;
        }
        return realMapper.existsReversedChainByKeys(bizKeys) > 0;
    }

    @Override
    public List<HistoryRealFactDTO> listRealFactsByBatch(String period, Long batchId) {
        if (period == null || period.isBlank() || batchId == null) {
            return Collections.emptyList();
        }
        return realMapper.selectHistoryByBatch(period, batchId);
    }

    // ==================== 写：调整执行 ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long adjustDetailAmount(Long detailId, BigDecimal targetAmount, Long operatorId, Long adjustId) {
        return adjustDetailAmount(detailId, targetAmount, null, operatorId, adjustId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long adjustDetailAmount(Long detailId, BigDecimal targetAmount, BigDecimal shareRatio,
                                   Long operatorId, Long adjustId) {
        ReceivedDetail oldDetail = detailMapper.selectById(detailId);
        if (oldDetail == null) {
            return null;
        }
        ReceivedDetail patch = new ReceivedDetail();
        patch.setPerformanceAmount(MoneyUtil.round2(targetAmount));
        if (shareRatio != null) {
            patch.setShareRatio(shareRatio);
        }
        return supersede(oldDetail, patch, operatorId, adjustId).getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long addMemberDetail(Long templateDetailId, Long employeeId, String employeeCode, Long deptId,
                                String roleType, BigDecimal amount, BigDecimal shareRatio,
                                Long operatorId, Long adjustId) {
        ReceivedDetail template = detailMapper.selectById(templateDetailId);
        if (template == null) {
            return null;
        }
        ReceivedDetail n = new ReceivedDetail();
        n.setContractId(template.getContractId());
        n.setEmployeeId(employeeId);
        n.setDeptId(deptId != null ? deptId : template.getDeptId());
        n.setEmployeeExternalCode(employeeCode);
        n.setRoleType(roleType);
        n.setRoleName(roleType);
        n.setShareRatio(shareRatio);
        n.setPerformanceAmount(MoneyUtil.round2(amount == null ? BigDecimal.ZERO : amount));
        n.setFeeItem(template.getFeeItem());
        n.setPeriod(template.getPeriod());
        n.setEffectiveDate(template.getEffectiveDate());
        n.setExpireDate(null);
        // sourceKey 用 MANUAL-RADJ 虚拟锚点：避开导入事实幂等键，同一调整单重试时键稳定
        String orderNo = "";
        String contractNo = "";
        ReceivedContract contract = contractMapper.selectById(template.getContractId());
        if (contract != null) {
            orderNo = contract.getOrderNo() == null ? "" : contract.getOrderNo();
            contractNo = contract.getContractNo() == null ? "" : contract.getContractNo();
        }
        n.setSourceKey(orderNo + "|" + contractNo + "|MANUAL-RADJ-" + adjustId + "-" + employeeId
            + "|" + (template.getFeeItem() == null ? "" : template.getFeeItem())
            + "|" + (roleType == null ? "" : roleType));
        // 手工新增行不归属任何导入批次/归一化记录/原始实收审批单
        n.setSourceBatchId(null);
        n.setNormalizedRecordId(null);
        n.setReceivedApplyId(template.getReceivedApplyId());
        n.setDetailStatus(STATUS_ACTIVE);
        n.setAdjustId(adjustId);
        n.setReversalType(null);
        n.setRefundOfDetailId(null);
        n.setOperatorId(operatorId);
        detailMapper.insert(n);
        refreshContractAggregate(n.getContractId());
        log.info("[实收新增角色人] 明细插入完成：templateDetailId={}, newDetailId={}, adjustId={}, employeeId={}",
            templateDetailId, n.getId(), adjustId, employeeId);
        return n.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long createDetailFromContract(String orderNo, String contractNo, String period, String bizType,
                                         Long employeeId, String employeeCode, Long deptId,
                                         String roleType, BigDecimal amount, BigDecimal shareRatio,
                                         Long operatorId, Long adjustId) {
        // 1. 找或建实收合同壳：按 orderNo 优先，contractNo 退化，不限 sourceType
        ReceivedContract contract = null;
        if (orderNo != null && !orderNo.isBlank()) {
            contract = contractMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ReceivedContract>()
                    .eq(ReceivedContract::getOrderNo, orderNo)
                    .eq(ReceivedContract::getPeriod, period)
                    .last("LIMIT 1"));
        }
        if (contract == null && contractNo != null && !contractNo.isBlank()) {
            contract = contractMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ReceivedContract>()
                    .eq(ReceivedContract::getContractNo, contractNo)
                    .eq(ReceivedContract::getPeriod, period)
                    .last("LIMIT 1"));
        }
        // 新签事实镜像：合同无实收时建的虚拟实收 = 新签的全字段镜像，仅金额=delta 不同，
        // 故壳级/明细级字段统一回源该新签事实（调用方入参只带得到员工/角色/金额）
        PerformanceFactSummaryDTO mirror = realMapper.selectExpectMirror(orderNo, contractNo, period, employeeId);
        if (contract == null) {
            // 合同无实收壳：手工建一条 MANUAL 壳（后续真实实收到账自动合并到同一个 contract_id 聚合）
            contract = new ReceivedContract();
            contract.setOrderNo(orderNo != null ? orderNo : (mirror == null ? null : mirror.getOrderNo()));
            contract.setContractNo(contractNo != null ? contractNo : (mirror == null ? null : mirror.getContractNo()));
            // biz_type 回源新签事实的真实业务类型（调用方以 factType 占位、非业务类型）；取不到退回入参
            String realBizType = mirror == null ? null : mirror.getBizType();
            contract.setBizType(realBizType != null && !realBizType.isBlank() ? realBizType : bizType);
            contract.setPeriod(period);
            contract.setSourceType("MANUAL");
            // business_date NOT NULL：镜像新签签约/成交日期，缺失时才兜底当期月末
            contract.setBusinessDate(mirror != null && mirror.getBusinessDate() != null
                ? mirror.getBusinessDate() : periodEndFallback(period));
            contract.setDeptId(mirror == null ? null : mirror.getDeptId());
            contract.setPropertyAddress(mirror == null ? null : mirror.getPropertyAddress());
            contract.setPeriodTotalReceived(BigDecimal.ZERO);
            contract.setItemCount(0);
            contractMapper.insert(contract);
            log.info("[实收-无壳建壳] 合同无实收合同壳，自动创建：adjustId={}, contractNo={}, orderNo={}",
                adjustId, contractNo, orderNo);
        }
        Long contractId = contract.getId();

        // 2. 组装实收明细行
        ReceivedDetail n = new ReceivedDetail();
        n.setContractId(contractId);
        n.setEmployeeId(employeeId);
        // 字段回退链：调用方入参 → 新签事实镜像 → 壳/默认，实现「新签全字段镜像」
        Long effDeptId = deptId != null ? deptId
            : (mirror != null && mirror.getDeptId() != null ? mirror.getDeptId() : contract.getDeptId());
        String effRoleType = roleType != null ? roleType : (mirror == null ? null : mirror.getRoleType());
        n.setDeptId(effDeptId);
        n.setEmployeeExternalCode(employeeCode != null ? employeeCode
            : (mirror == null ? null : mirror.getEmployeeCode()));
        n.setRoleType(effRoleType);
        n.setRoleName(mirror != null && mirror.getRoleName() != null ? mirror.getRoleName() : effRoleType);
        n.setShareRatio(shareRatio != null ? shareRatio
            : (mirror != null && mirror.getShareRatio() != null ? mirror.getShareRatio() : BigDecimal.ONE));
        n.setPerformanceAmount(MoneyUtil.round2(amount == null ? BigDecimal.ZERO : amount));
        n.setFeeItem(null); // 合同壳无 feeItem 模板，空值即可
        n.setPeriod(period);
        // effectiveDate 兜底：合同壳 businessDate → 或默认 today
        java.time.LocalDate eff = null;
        if (contract.getBusinessDate() != null) {
            eff = contract.getBusinessDate().toLocalDate();
        }
        if (eff == null) {
            eff = java.time.LocalDate.now();
        }
        n.setEffectiveDate(eff);
        n.setExpireDate(null);
        // sourceKey：新签调整 MANUAL-RADJ 虚拟锚点（不撞导入幂等键）
        String on = orderNo == null ? "" : orderNo;
        String cn = contractNo == null ? "" : contractNo;
        n.setSourceKey(on + "|" + cn + "|MANUAL-RADJ-" + adjustId + "-" + employeeId
            + "|" + (effRoleType == null ? "" : effRoleType));
        n.setSourceBatchId(null);
        n.setNormalizedRecordId(null);
        n.setReceivedApplyId(null); // 手工虚拟实收不归属任何审批单
        n.setDetailStatus(STATUS_ACTIVE);
        n.setAdjustId(adjustId);
        n.setOperatorId(operatorId);
        detailMapper.insert(n);
        refreshContractAggregate(contractId);
        log.info("[实收-无壳建行] adjustId={}, employeeId={}, amount={}, contractId={}",
            adjustId, employeeId, n.getPerformanceAmount(), contractId);
        return n.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long createRefundDetail(Long templateDetailId, String refundPeriod, Long operatorId, Long refundId) {
        if (templateDetailId == null || refundPeriod == null || refundPeriod.isBlank()) {
            return null;
        }
        ReceivedDetail template = detailMapper.selectById(templateDetailId);
        if (template == null || !STATUS_ACTIVE.equals(template.getDetailStatus())) {
            return null;
        }
        ReceivedContract templateContract = contractMapper.selectById(template.getContractId());
        String orderNo = templateContract == null ? null : templateContract.getOrderNo();
        String contractNo = templateContract == null ? null : templateContract.getContractNo();

        // 1. 找或建「退单月份」合同壳：退单负流水必须落在退单月，不能挂在原期间壳上
        ReceivedContract shell = findOrCreateContractShell(orderNo, contractNo, refundPeriod, templateContract);

        // 2. 镜像模板明细，金额取负
        ReceivedDetail n = new ReceivedDetail();
        n.setContractId(shell.getId());
        n.setEmployeeId(template.getEmployeeId());
        n.setDeptId(template.getDeptId());
        n.setEmployeeExternalCode(template.getEmployeeExternalCode());
        n.setRoleType(template.getRoleType());
        n.setRoleName(template.getRoleName());
        n.setShareRatio(template.getShareRatio());
        n.setPerformanceAmount(MoneyUtil.round2(
            template.getPerformanceAmount() == null ? BigDecimal.ZERO : template.getPerformanceAmount().negate()));
        n.setFeeItem(template.getFeeItem());
        n.setPeriod(refundPeriod);
        n.setEffectiveDate(refundPeriod.equals(template.getPeriod()) && template.getEffectiveDate() != null
            ? template.getEffectiveDate() : periodEndFallback(refundPeriod).toLocalDate());
        n.setExpireDate(null);
        String on = orderNo == null ? "" : orderNo;
        String cn = contractNo == null ? "" : contractNo;
        n.setSourceKey(on + "|" + cn + "|MANUAL-RFND-" + refundId + "-" + templateDetailId
            + "|" + (template.getFeeItem() == null ? "" : template.getFeeItem())
            + "|" + (template.getRoleType() == null ? "" : template.getRoleType()));
        n.setSourceBatchId(null);
        n.setNormalizedRecordId(null);
        n.setReceivedApplyId(null);
        n.setDetailStatus(STATUS_ACTIVE);
        n.setOperatorId(operatorId);
        n.setAdjustId(null);
        n.setReversalType(null);
        n.setRefundOfDetailId(template.getId());
        detailMapper.insert(n);
        refreshContractAggregate(shell.getId());
        log.info("[实收-退单冲减] refundId={}, templateDetailId={}, newDetailId={}, period={}, amount={}",
            refundId, templateDetailId, n.getId(), refundPeriod, n.getPerformanceAmount());
        return n.getId();
    }

    /**
     * 退单落账月的实收合同壳：按（订单号优先，合同号退化）+ 期间定位；不存在则镜像原壳建一条。
     */
    private ReceivedContract findOrCreateContractShell(String orderNo, String contractNo,
                                                       String period, ReceivedContract template) {
        ReceivedContract shell = null;
        if (orderNo != null && !orderNo.isBlank()) {
            shell = contractMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ReceivedContract>()
                    .eq(ReceivedContract::getOrderNo, orderNo)
                    .eq(ReceivedContract::getPeriod, period)
                    .last("LIMIT 1"));
        }
        if (shell == null && contractNo != null && !contractNo.isBlank()) {
            shell = contractMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ReceivedContract>()
                    .eq(ReceivedContract::getContractNo, contractNo)
                    .eq(ReceivedContract::getPeriod, period)
                    .last("LIMIT 1"));
        }
        if (shell != null) {
            return shell;
        }
        shell = new ReceivedContract();
        shell.setOrderNo(orderNo != null ? orderNo : (template == null ? null : template.getOrderNo()));
        shell.setContractNo(contractNo != null ? contractNo : (template == null ? null : template.getContractNo()));
        shell.setBizType(template == null ? null : template.getBizType());
        shell.setPeriod(period);
        shell.setSourceType("MANUAL");
        shell.setBusinessDate(template != null && template.getBusinessDate() != null
            ? template.getBusinessDate() : periodEndFallback(period));
        shell.setDeptId(template == null ? null : template.getDeptId());
        shell.setPropertyAddress(template == null ? null : template.getPropertyAddress());
        shell.setPeriodTotalReceived(BigDecimal.ZERO);
        shell.setItemCount(0);
        contractMapper.insert(shell);
        log.info("[实收-退单建壳] period={}, contractNo={}, orderNo={}, contractId={}",
            period, contractNo, orderNo, shell.getId());
        return shell;
    }

    /**
     * 退单负实收挂单：实收管理页按审批单维度展示，未挂单的负明细页面不可见，
     * 故按退单号直建一张 APPROVED 单（无流程实例，与当月正常实收单并存；
     * 唯一索引 {@code uk_rapp_period_order} 已排除 TD 单号），再把负明细绑定上去并重算金额/条数。
     * <p>
     * 幂等：同退单号已有单据时直接复用（退单执行回调重试场景）。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long createRefundApply(String refundNo, String refundPeriod, String orderNo, String contractNo,
                                  List<Long> detailIds, Long operatorId) {
        if (detailIds == null || detailIds.isEmpty()
            || refundPeriod == null || refundPeriod.isBlank()) {
            return null;
        }
        ReceivedApply apply = findRefundApply(refundNo);
        if (apply == null) {
            ReceivedDetail first = detailMapper.selectById(detailIds.get(0));
            ReceivedContract shell = first == null ? null : contractMapper.selectById(first.getContractId());
            apply = new ReceivedApply();
            // 单号沿用退单号（TD 前缀），结佣发起候选查询据此排除负实收
            apply.setApplyNo(refundNo);
            apply.setPeriod(refundPeriod);
            apply.setOrderNo(orderNo);
            apply.setContractNo(contractNo);
            if (shell != null) {
                apply.setBizType(shell.getBizType());
                apply.setPropertyAddress(shell.getPropertyAddress());
                apply.setBusinessDate(shell.getBusinessDate());
                apply.setDeptId(shell.getDeptId());
            }
            apply.setReceivedAmount(BigDecimal.ZERO);
            apply.setExpectedAmount(BigDecimal.ZERO);
            apply.setItemCount(0);
            // 退单执行即终态：直接 APPROVED，无工作流实例
            apply.setStatus(ReceivedApplyStatus.APPROVED);
            apply.setCurrentNode(null);
            apply.setApplicantId(operatorId);
            apply.setApproverId(operatorId);
            apply.setApproveTime(java.time.LocalDateTime.now());
            try {
                applyMapper.insert(apply);
            } catch (org.springframework.dao.DuplicateKeyException e) {
                // 并发重试：另一事务已建同单号单据，取回复用
                apply = findRefundApply(refundNo);
                if (apply == null) {
                    log.warn("[实收-退单挂单] 建单冲突且找不到退单单据，跳过：refundNo={}", refundNo);
                    return null;
                }
            }
        }
        // 负明细归属退单冲减单
        detailMapper.update(null,
            new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<ReceivedDetail>()
                .in(ReceivedDetail::getId, detailIds)
                .set(ReceivedDetail::getReceivedApplyId, apply.getId()));
        // 单据金额/条数按绑定后的 ACTIVE 明细实时重算
        List<ReceivedDetail> bound = detailMapper.selectList(
            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ReceivedDetail>()
                .eq(ReceivedDetail::getReceivedApplyId, apply.getId())
                .eq(ReceivedDetail::getDetailStatus, STATUS_ACTIVE));
        apply.setItemCount(bound.size());
        apply.setReceivedAmount(bound.stream()
            .map(d -> d.getPerformanceAmount() == null ? BigDecimal.ZERO : d.getPerformanceAmount())
            .reduce(BigDecimal.ZERO, BigDecimal::add));
        applyMapper.updateById(apply);
        log.info("[实收-退单挂单] refundNo={}, applyId={}, period={}, 明细数={}, 实收合计={}",
            refundNo, apply.getId(), refundPeriod, apply.getItemCount(), apply.getReceivedAmount());
        return apply.getId();
    }

    /** 按退单号（同时是审批单号）查退单冲减单，无则返回 null。 */
    private ReceivedApply findRefundApply(String refundNo) {
        if (refundNo == null || refundNo.isBlank()) {
            return null;
        }
        return applyMapper.selectOne(
            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ReceivedApply>()
                .eq(ReceivedApply::getApplyNo, refundNo)
                .last("LIMIT 1"));
    }

    /**
     * 新签事实缺 business_date 时的兜底：当期月末 23:59:59，再不行取当前时间（该列 NOT NULL）。
     */
    private java.time.LocalDateTime periodEndFallback(String period) {
        if (period != null && period.matches("\\d{4}-\\d{2}")) {
            return java.time.YearMonth.parse(period).atEndOfMonth().atTime(23, 59, 59);
        }
        return java.time.LocalDateTime.now();
    }

    // ==================== 退化闸门支撑 ====================

    @Override
    public long countDistinctReceivedOrders(String contractNo) {
        if (contractNo == null || contractNo.isBlank()) {
            return 0L;
        }
        return contractMapper.countDistinctOrderNos(contractNo);
    }

    // ==================== 内部方法 ====================

    /**
     * 实收明细 supersede 状态机（对应 ReverseService.supersede）：
     * 旧 ACTIVE → REVERSED(reversal_type=SUPERSEDE)、expire_date=新生效日前一天，
     * 再插同 source_key 的新 ACTIVE 行（先改旧行再插新行，满足部分唯一索引），
     * 重算合同聚合并发冲销事件。
     */
    private ReceivedDetail supersede(ReceivedDetail oldDetail, ReceivedDetail patch,
                                     Long operatorId, Long adjustId) {
        if (!STATUS_ACTIVE.equals(oldDetail.getDetailStatus())) {
            throw new IllegalStateException(
                "实收明细状态不可冲销：detailId=" + oldDetail.getId()
                    + ", currentStatus=" + oldDetail.getDetailStatus());
        }
        ReceivedDetail newDetail = copyForSupersede(oldDetail);
        if (patch.getPerformanceAmount() != null) {
            newDetail.setPerformanceAmount(patch.getPerformanceAmount());
        }
        if (patch.getShareRatio() != null) {
            newDetail.setShareRatio(patch.getShareRatio());
        }
        if (patch.getDeptId() != null) {
            newDetail.setDeptId(patch.getDeptId());
        }
        newDetail.setAdjustId(adjustId);
        newDetail.setOperatorId(operatorId);

        // 1. 旧行转 REVERSED（必须先于新行 INSERT）
        oldDetail.setDetailStatus(STATUS_REVERSED);
        oldDetail.setReversalType(REASON_SUPERSEDE);
        oldDetail.setOperatorId(operatorId);
        if (newDetail.getEffectiveDate() != null) {
            oldDetail.setExpireDate(newDetail.getEffectiveDate().minusDays(1));
        }
        detailMapper.updateById(oldDetail);

        // 2. 插新 ACTIVE 行（同 source_key；source_batch_id 保留但 adjust_id 非空，批次冲销会跳过）
        detailMapper.insert(newDetail);

        // 3. 重算合同级聚合 + 发冲销事件（结佣域分治表联动）
        refreshContractAggregate(newDetail.getContractId());
        emitReversed(oldDetail.getPeriod(), List.of(oldDetail.getId()), REASON_SUPERSEDE);
        log.info("[实收冲销-替换] 明细替换完成：oldDetailId={}, newDetailId={}",
            oldDetail.getId(), newDetail.getId());
        return newDetail;
    }

    /** 复制全部业务字段（含 receivedApplyId 关联）；状态/冲销字段按新 ACTIVE 行重置。 */
    private ReceivedDetail copyForSupersede(ReceivedDetail oldDetail) {
        ReceivedDetail n = new ReceivedDetail();
        n.setContractId(oldDetail.getContractId());
        n.setEmployeeId(oldDetail.getEmployeeId());
        n.setDeptId(oldDetail.getDeptId());
        n.setEmployeeExternalCode(oldDetail.getEmployeeExternalCode());
        n.setRoleType(oldDetail.getRoleType());
        n.setRoleName(oldDetail.getRoleName());
        n.setShareRatio(oldDetail.getShareRatio());
        n.setPerformanceAmount(oldDetail.getPerformanceAmount());
        n.setFeeItem(oldDetail.getFeeItem());
        n.setPeriod(oldDetail.getPeriod());
        n.setEffectiveDate(oldDetail.getEffectiveDate());
        n.setExpireDate(null);
        n.setSourceKey(oldDetail.getSourceKey());
        n.setSourceBatchId(oldDetail.getSourceBatchId());
        n.setNormalizedRecordId(oldDetail.getNormalizedRecordId());
        n.setReceivedApplyId(oldDetail.getReceivedApplyId());
        n.setDetailStatus(STATUS_ACTIVE);
        n.setAdjustId(null);
        n.setReversalType(null);
        n.setRefundOfDetailId(oldDetail.getRefundOfDetailId());
        return n;
    }

    /** 重算实收合同级聚合（period_total_received / item_count），与明细 ACTIVE 集合保持一致。 */
    private void refreshContractAggregate(Long contractId) {
        if (contractId == null) {
            return;
        }
        List<ReceivedDetail> active = detailMapper.selectList(
            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ReceivedDetail>()
                .eq(ReceivedDetail::getContractId, contractId)
                .eq(ReceivedDetail::getDetailStatus, STATUS_ACTIVE));
        ReceivedContract contract = contractMapper.selectById(contractId);
        if (contract == null) {
            return;
        }
        contract.setItemCount(active.size());
        contract.setPeriodTotalReceived(active.stream()
            .map(d -> d.getPerformanceAmount() == null ? BigDecimal.ZERO : d.getPerformanceAmount())
            .reduce(BigDecimal.ZERO, BigDecimal::add));
        contractMapper.updateById(contract);
    }

    private void emitReversed(String period, List<Long> detailIds, String reason) {
        if (detailIds == null || detailIds.isEmpty()) {
            return;
        }
        PerformanceFactReversedEvent event = new PerformanceFactReversedEvent();
        event.setPeriod(period);
        List<String> ids = new ArrayList<>(detailIds.size());
        for (Long id : detailIds) {
            ids.add(String.valueOf(id));
        }
        event.setFactIds(ids);
        event.setReason(reason);
        eventPort.emit(event);
    }
}
