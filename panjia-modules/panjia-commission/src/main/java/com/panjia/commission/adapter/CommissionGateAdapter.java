package com.panjia.commission.adapter;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.panjia.commission.domain.ApplicationStatus;
import com.panjia.commission.domain.CommissionApplication;
import com.panjia.commission.mapper.CommissionApplicationMapper;
import com.panjia.contracts.port.CommissionGatePort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 结佣闸门跨域适配器（panjia-commission 实现 contracts {@link CommissionGatePort}）。
 * <p>
 * 供 performance 域在发起/执行新签调整前校验结佣单是否已审批锁定（LOCKED），
 * 避免新签事实 supersede 破坏已审批并计入工资的结佣数据。
 * <p>
 * 查询口径与 {@code findActiveApplication} 一致：合同号 / 订单号双键 OR 匹配，
 * 兼容结佣单按订单号建单的历史场景。
 */
@Service
@RequiredArgsConstructor
public class CommissionGateAdapter implements CommissionGatePort {

    private final CommissionApplicationMapper applicationMapper;

    @Override
    public boolean isCommissionLocked(String period, String contractNo) {
        if (period == null || period.isBlank() || contractNo == null || contractNo.isBlank()) {
            return false;
        }
        Long count = applicationMapper.selectCount(new LambdaQueryWrapper<CommissionApplication>()
            .eq(CommissionApplication::getPeriod, period)
            .and(w -> w.eq(CommissionApplication::getContractNo, contractNo)
                .or().eq(CommissionApplication::getOrderNo, contractNo))
            .eq(CommissionApplication::getStatus, ApplicationStatus.LOCKED));
        return count != null && count > 0;
    }
}
