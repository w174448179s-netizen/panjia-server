package com.panjia.commission.adapter;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.panjia.commission.domain.AdjustStatus;
import com.panjia.commission.domain.ApplicationStatus;
import com.panjia.commission.domain.CommissionAdjust;
import com.panjia.commission.domain.CommissionApplication;
import com.panjia.commission.mapper.CommissionAdjustMapper;
import com.panjia.commission.mapper.CommissionApplicationMapper;
import com.panjia.contracts.port.CommissionConsumptionQueryPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 结佣消费状态查询端口实现（入站适配器）。
 * <p>
 * 定义在 panjia-contracts，由结佣域实现，供业绩域撤销批次前置校验调用。
 * 拦截口径见 {@link CommissionConsumptionQueryPort}：
 * <ol>
 *   <li>已提交/已通过/已锁定且<b>持有流程实例</b>的结佣单（正式审批链路）→ 禁止；</li>
 *   <li>已驳回结佣单（流程产物，可能重新提交）→ 禁止；</li>
 *   <li>结佣调整单存在未作废记录（EXECUTED 已改数据 / SUBMITTED 审批中 / REJECTED 驳回）→ 禁止；</li>
 *   <li>DRAFT 草稿单、历史导入 LOCKED 无流程单、CANCELLED 终态单均不拦截。</li>
 * </ol>
 */
@Component
@RequiredArgsConstructor
public class CommissionConsumptionQueryAdapter implements CommissionConsumptionQueryPort {

    private final CommissionApplicationMapper applicationMapper;
    private final CommissionAdjustMapper adjustMapper;

    @Override
    public RevokeCheckResult checkRevocable(String period,
                                            Collection<String> contractNos,
                                            Collection<String> orderNos) {
        List<String> contracts = sanitize(contractNos);
        List<String> orders = sanitize(orderNos);
        if ((contracts.isEmpty() && orders.isEmpty()) || period == null || period.isBlank()) {
            return RevokeCheckResult.ok();
        }

        // 校验①：已进入正式审批链路的结佣单（有流程实例的提交/通过/锁定，或已驳回）
        LambdaQueryWrapper<CommissionApplication> appQuery = new LambdaQueryWrapper<CommissionApplication>()
            .eq(CommissionApplication::getPeriod, period)
            .and(bizKey -> {
                if (!contracts.isEmpty() && !orders.isEmpty()) {
                    bizKey.in(CommissionApplication::getContractNo, contracts)
                        .or().in(CommissionApplication::getOrderNo, orders);
                } else if (!contracts.isEmpty()) {
                    bizKey.in(CommissionApplication::getContractNo, contracts);
                } else {
                    bizKey.in(CommissionApplication::getOrderNo, orders);
                }
            })
            .and(w -> w
                .nested(x -> x
                    .in(CommissionApplication::getStatus,
                        ApplicationStatus.SUBMITTED, ApplicationStatus.APPROVED, ApplicationStatus.LOCKED)
                    .isNotNull(CommissionApplication::getProcessInstanceId))
                .or().eq(CommissionApplication::getStatus, ApplicationStatus.REJECTED));
        Long activeAppCount = applicationMapper.selectCount(appQuery);
        if (activeAppCount != null && activeAppCount > 0) {
            return RevokeCheckResult.reject(
                "该批次涉及合同存在已提交/审批/锁定/驳回的结佣申请单（" + activeAppCount
                    + " 张），禁止撤销，请先在结佣模块作废结佣单");
        }

        // 校验②：存在未作废的结佣调整单（EXECUTED 已改写事实/金额，SUBMITTED 审批中，REJECTED 可能重提）
        // 结佣调整单 contract_no 存业务键（合同号优先，为空时落订单号），故事实侧只拿到订单号
        // 的合同也要按订单号命中
        LambdaQueryWrapper<CommissionAdjust> adjustQuery = new LambdaQueryWrapper<CommissionAdjust>()
            .eq(CommissionAdjust::getPeriod, period)
            .ne(CommissionAdjust::getStatus, AdjustStatus.CANCELLED);
        if (!contracts.isEmpty() && !orders.isEmpty()) {
            adjustQuery.and(k -> k.in(CommissionAdjust::getContractNo, contracts)
                .or().in(CommissionAdjust::getContractNo, orders));
        } else if (!contracts.isEmpty()) {
            adjustQuery.in(CommissionAdjust::getContractNo, contracts);
        } else {
            adjustQuery.in(CommissionAdjust::getContractNo, orders);
        }
        Long adjustCount = adjustMapper.selectCount(adjustQuery);
        if (adjustCount != null && adjustCount > 0) {
            return RevokeCheckResult.reject(
                "该批次涉及合同存在结佣调整单（" + adjustCount + " 张），禁止撤销，请先处理调整单");
        }

        return RevokeCheckResult.ok();
    }

    /** 去空白、去重；空入参返回空列表（避免 IN () 非法 SQL）。 */
    private List<String> sanitize(Collection<String> source) {
        if (CollectionUtils.isEmpty(source)) {
            return List.of();
        }
        return source.stream()
            .filter(s -> s != null && !s.isBlank())
            .map(String::trim)
            .distinct()
            .collect(Collectors.toList());
    }
}
