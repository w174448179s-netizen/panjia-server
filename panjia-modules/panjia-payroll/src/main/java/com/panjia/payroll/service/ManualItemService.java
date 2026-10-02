package com.panjia.payroll.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.panjia.contracts.port.PeriodCloseQueryPort;
import com.panjia.payroll.domain.BatchStatus;
import com.panjia.payroll.domain.ManualItem;
import com.panjia.payroll.domain.PayrollBatch;
import com.panjia.payroll.mapper.ManualItemMapper;
import com.panjia.payroll.mapper.PayrollBatchMapper;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.StringUtils;
import org.springframework.stereotype.Service;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** 手工录入项服务（奖金/其他收入/其他支出） */
@Service
@RequiredArgsConstructor
public class ManualItemService {

    /**
     * 冻结手工项增删的批次状态。
     * <p>财务规则：提交审批即定稿——审批中/已通过/已锁定/已发放期间，奖金与收支一律不允许新增或删除；
     * 驳回（退回 CALCULATED）后自动解冻，改数重算再提交。CALCULATING 为算薪取数瞬态，一并挡下。
     */
    private static final Set<BatchStatus> FROZEN_BATCH_STATUS = EnumSet.of(
        BatchStatus.CALCULATING,
        BatchStatus.REVIEWING,
        BatchStatus.APPROVED,
        BatchStatus.LOCKED,
        BatchStatus.PAID
    );

    private final ManualItemMapper mapper;
    private final PayrollBatchMapper batchMapper;
    /** 期间封账跨域查询（performance 域实现）：锁定批次记录缺失等异常场景的兜底冻结信号 */
    private final PeriodCloseQueryPort periodCloseQueryPort;

    public Long create(ManualItem item, Long operatorId) {
        if (item.getItemType() == null) {
            throw new ServiceException("请选择录入类型");
        }
        assertPeriodEditable(item.getPeriod());
        item.setStatus("APPROVED"); // V1 直接生效，后续可走审批
        item.setApplyBy(operatorId);
        item.setApproveBy(operatorId);
        mapper.insert(item);
        return item.getId();
    }

    public List<ManualItem> listByPeriod(String period) {
        return mapper.selectList(
            new LambdaQueryWrapper<ManualItem>()
                .eq(ManualItem::getPeriod, period)
                .orderByDesc(ManualItem::getCreateTime));
    }

    public ManualItem getById(Long id) {
        return mapper.selectById(id);
    }

    public void delete(Long id, Long operatorId) {
        ManualItem item = mapper.selectById(id);
        if (item == null) {
            throw new ServiceException("记录不存在或已被删除");
        }
        assertPeriodEditable(item.getPeriod());
        mapper.deleteById(id);
    }

    /**
     * 校验期间是否允许新增/删除手工项（奖金/其他收入/其他支出）。
     * <p>双重冻结信号，任一命中即拒绝：
     * <ol>
     *   <li>该期间工资批次处于审批中及以后状态（提交审批即定稿）；</li>
     *   <li>该业绩期间已封账（兜底，防止批次记录缺失等异常数据绕过）。</li>
     * </ol>
     *
     * @param period 归属期间 YYYY-MM
     */
    private void assertPeriodEditable(String period) {
        if (StringUtils.isBlank(period)) {
            throw new ServiceException("归属期间不能为空");
        }

        // 1. 批次状态：任一 dept_scope 的批次冻结即冻结（uk_batch_period_scope 每范围一条）
        PayrollBatch frozenBatch = batchMapper.selectOne(
            new LambdaQueryWrapper<PayrollBatch>()
                .eq(PayrollBatch::getPeriod, period)
                .in(PayrollBatch::getStatus, FROZEN_BATCH_STATUS)
                .last("LIMIT 1"));
        if (frozenBatch != null) {
            throw new ServiceException(frozenMessage(frozenBatch.getStatus()));
        }

        // 2. 期间封账兜底（总监锁定自动封账；解封后本信号解除）
        if (periodCloseQueryPort.isClosed(period)) {
            throw new ServiceException(
                "该业绩期间已封账，奖金与收支不允许新增或删除；如需纠错请先在「结佣明细」页解封该期间");
        }
    }

    private String frozenMessage(BatchStatus status) {
        return switch (status) {
            case CALCULATING -> "该期间工资正在计算中，请稍后再操作";
            case REVIEWING -> "该期间工资批次审批中，奖金与收支已冻结；如需调整请待审批驳回后修改并重算工资";
            case APPROVED -> "该期间工资已审核通过，奖金与收支已冻结；如需调整请驳回审批后修改并重算工资";
            case LOCKED -> "该期间工资已锁定封账，奖金与收支已冻结；如需纠错请先在「结佣明细」页解封该期间";
            case PAID -> "该期间工资已发放，奖金与收支不允许新增或删除";
            default -> "该期间工资批次已冻结，不允许新增或删除";
        };
    }

    /** 取某期间已审批的手工项，按员工分组（算薪用） */
    public Map<Long, List<ManualItem>> loadApprovedForPeriod(String period) {
        List<ManualItem> items = mapper.selectList(
            new LambdaQueryWrapper<ManualItem>()
                .eq(ManualItem::getPeriod, period)
                .eq(ManualItem::getStatus, "APPROVED"));
        return items.stream().collect(Collectors.groupingBy(ManualItem::getEmployeeId));
    }
}
