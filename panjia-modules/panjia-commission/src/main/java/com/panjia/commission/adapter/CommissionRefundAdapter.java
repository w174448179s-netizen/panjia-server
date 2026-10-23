package com.panjia.commission.adapter;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.panjia.commission.domain.ApplicationStatus;
import com.panjia.commission.domain.CommissionApplication;
import com.panjia.commission.domain.CommissionItem;
import com.panjia.commission.domain.ItemStatus;
import com.panjia.commission.mapper.CommissionApplicationMapper;
import com.panjia.commission.mapper.CommissionItemMapper;
import com.panjia.contracts.dto.CommissionRefundLine;
import com.panjia.contracts.port.CommissionRefundPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.utils.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 退单结佣端口实现。
 * <p>
 * 退单执行时，把合同下「已结佣（APPROVED）」的新签明细在退单月镜像为负明细，
 * 金额取负、绑定退单生成的负新签事实，状态直接 APPROVED（退单已单独审批），
 * 使退单月份的算薪立即按负值计提成；未结佣部分不在此冲减，由后续发起结佣
 * 读取负新签事实自然抵扣（负事实已被负明细占用时不会再被选中）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CommissionRefundAdapter implements CommissionRefundPort {

    /** 结佣明细源类型：新签 */
    private static final String FACT_TYPE_EXPECT = "PERF_EXPECT";

    /** 退单冲减行来源标识 */
    private static final String SOURCE_MANUAL = "MANUAL";

    private final CommissionItemMapper itemMapper;
    private final CommissionApplicationMapper applicationMapper;

    @Override
    public BigDecimal sumSettledExpectAmount(String contractNo, String orderNo) {
        return listSettledItems(contractNo, orderNo).stream()
            .map(CommissionItem::getAmount)
            .filter(Objects::nonNull)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    @Override
    public List<CommissionRefundLine> listSettledLines(String contractNo, String orderNo) {
        List<CommissionRefundLine> lines = new ArrayList<>();
        for (CommissionItem item : listSettledItems(contractNo, orderNo)) {
            if (item.getAmount() == null || item.getPerformanceFactId() == null) {
                continue;
            }
            CommissionRefundLine line = new CommissionRefundLine();
            line.setEmployeeId(item.getEmployeeId());
            line.setDeptId(item.getDeptId());
            line.setEmployeeCode(item.getEmployeeCode());
            line.setContractNo(item.getContractNo());
            line.setOrderNo(item.getOrderNo());
            line.setBizType(item.getBizType());
            line.setRoleType(item.getRoleType());
            line.setRoleName(item.getRoleName());
            line.setFeeItem(item.getFeeItem());
            line.setShareRatio(item.getShareRatio());
            line.setAmount(item.getAmount().negate());
            lines.add(line);
        }
        return lines;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public BigDecimal createRefundReversal(String contractNo, String orderNo, String refundPeriod,
                                           Long refundId, Long operatorId,
                                           Map<Long, Long> expectFactIdMapping) {
        List<CommissionItem> items = listSettledItems(contractNo, orderNo);
        if (items.isEmpty()) {
            return BigDecimal.ZERO;
        }
        Long applicationId = resolveContainer(contractNo, orderNo, refundPeriod, refundId, operatorId);
        BigDecimal sum = BigDecimal.ZERO;
        int count = 0;
        for (CommissionItem item : items) {
            Long negativeFactId = expectFactIdMapping == null
                ? null : expectFactIdMapping.get(item.getPerformanceFactId());
            if (negativeFactId == null) {
                continue;
            }
            // 幂等：该负事实已被绑定（重复执行退单）则跳过
            Long exists = itemMapper.selectCount(new LambdaQueryWrapper<CommissionItem>()
                .eq(CommissionItem::getPerformanceFactId, negativeFactId));
            if (exists != null && exists > 0) {
                continue;
            }
            CommissionItem n = new CommissionItem();
            n.setApplicationId(applicationId);
            n.setPerformanceFactId(negativeFactId);
            n.setFactType(FACT_TYPE_EXPECT);
            n.setContractNo(item.getContractNo());
            n.setOrderNo(item.getOrderNo());
            n.setBusinessDate(item.getBusinessDate());
            n.setPropertyAddress(item.getPropertyAddress());
            n.setShareRatio(item.getShareRatio());
            n.setEmployeeCode(item.getEmployeeCode());
            n.setRoleName(item.getRoleName());
            n.setSourceKey("RFND-" + refundId + "-" + item.getId());
            n.setBatchId(item.getBatchId());
            n.setSource(SOURCE_MANUAL);
            n.setReceivedApplyId(item.getReceivedApplyId());
            n.setPeriod(refundPeriod);
            n.setApprovedMonth(refundPeriod);
            n.setEmployeeId(item.getEmployeeId());
            n.setDeptId(item.getDeptId());
            n.setBizType(item.getBizType());
            n.setRoleType(item.getRoleType());
            n.setFeeItem(item.getFeeItem());
            n.setAmount(item.getAmount().negate());
            n.setStatus(ItemStatus.APPROVED);
            n.setOriginReversed(false);
            itemMapper.insert(n);
            sum = sum.add(n.getAmount());
            count++;
        }
        if (count > 0) {
            refreshApplicationTotals(applicationId);
        }
        log.info("[退单结佣] 冲减明细生成：refundId={}, contractNo={}, 笔数={}, 冲减结佣={}, 落账月={}",
            refundId, contractNo, count, sum, refundPeriod);
        return sum;
    }

    /** 已结佣新签明细（APPROVED、PERF_EXPECT、正金额） */
    private List<CommissionItem> listSettledItems(String contractNo, String orderNo) {
        LambdaQueryWrapper<CommissionItem> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(CommissionItem::getStatus, ItemStatus.APPROVED)
            .eq(CommissionItem::getFactType, FACT_TYPE_EXPECT)
            .gt(CommissionItem::getAmount, BigDecimal.ZERO);
        if (StringUtils.isNotBlank(orderNo)) {
            wrapper.eq(CommissionItem::getOrderNo, orderNo);
        } else {
            wrapper.eq(CommissionItem::getContractNo, contractNo);
        }
        return itemMapper.selectList(wrapper);
    }

    /**
     * 负明细承载单：优先复用退单月同订单的活跃结佣单（避免 uk_capp_period_order 冲突），
     * 无则新建一张状态为 APPROVED 的退单冲减单。
     */
    private Long resolveContainer(String contractNo, String orderNo, String refundPeriod,
                                  Long refundId, Long operatorId) {
        LambdaQueryWrapper<CommissionApplication> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(CommissionApplication::getPeriod, refundPeriod)
            .in(CommissionApplication::getStatus, Arrays.asList(
                ApplicationStatus.DRAFT, ApplicationStatus.SUBMITTED,
                ApplicationStatus.APPROVED, ApplicationStatus.LOCKED));
        if (StringUtils.isNotBlank(orderNo)) {
            wrapper.eq(CommissionApplication::getOrderNo, orderNo);
        } else {
            wrapper.eq(CommissionApplication::getContractNo, contractNo);
        }
        wrapper.orderByDesc(CommissionApplication::getCreateTime).last("LIMIT 1");
        CommissionApplication existing = applicationMapper.selectOne(wrapper);
        if (existing != null) {
            return existing.getId();
        }
        CommissionApplication app = new CommissionApplication();
        app.setApplyNo("TD" + refundId);
        app.setPeriod(refundPeriod);
        app.setContractNo(contractNo);
        app.setOrderNo(orderNo);
        app.setItemCount(0);
        app.setTotalAmount(BigDecimal.ZERO);
        app.setExpectedAmount(BigDecimal.ZERO);
        app.setAligned(false);
        app.setStatus(ApplicationStatus.APPROVED);
        app.setApprovedMonth(refundPeriod);
        app.setApplicantId(operatorId);
        app.setApproverId(operatorId);
        app.setApproveTime(LocalDateTime.now());
        applicationMapper.insert(app);
        log.info("[退单结佣] 新建退单冲减结佣单：applyNo={}, period={}, contractNo={}",
            app.getApplyNo(), refundPeriod, contractNo);
        return app.getId();
    }

    /** 同步承载单的明细条数与合计（快照字段，避免与明细长期不一致） */
    private void refreshApplicationTotals(Long applicationId) {
        List<CommissionItem> items = itemMapper.selectList(new LambdaQueryWrapper<CommissionItem>()
            .eq(CommissionItem::getApplicationId, applicationId)
            .ne(CommissionItem::getStatus, ItemStatus.REVERSED));
        BigDecimal total = items.stream()
            .map(CommissionItem::getAmount)
            .filter(Objects::nonNull)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        CommissionApplication app = applicationMapper.selectById(applicationId);
        if (app == null) {
            return;
        }
        app.setItemCount(items.size());
        app.setTotalAmount(total);
        applicationMapper.updateById(app);
    }
}
