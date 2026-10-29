package com.panjia.commission.adapter;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.panjia.commission.domain.ApplicationStatus;
import com.panjia.commission.domain.CommissionApplication;
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
 *   <li>DRAFT 草稿单、历史导入 LOCKED 无流程单、CANCELLED 终态单均不拦截。</li>
 * </ol>
 * <p>
 * 注意：匹配只按合同/订单业务键，<b>不按批次期间过滤</b>——结佣单 period 落的是
 * 实收月，而其明细可引用更早月份的新签事实；撤新签批次时若按期间过滤，跨月锁定单
 * 会漏检，撤销将物理删除被 LOCKED 单引用的事实。
 */
@Component
@RequiredArgsConstructor
public class CommissionConsumptionQueryAdapter implements CommissionConsumptionQueryPort {

    private final CommissionApplicationMapper applicationMapper;

    /** 结佣消费业务键前缀：结佣单可能只记合同号或只记订单号，用前缀区分两个命名空间。 */
    private static final String KEY_CONTRACT = "C|";
    private static final String KEY_ORDER = "O|";

    @Override
    public RevokeCheckResult checkRevocable(String period,
                                            Collection<String> contractNos,
                                            Collection<String> orderNos) {
        List<String> contracts = sanitize(contractNos);
        List<String> orders = sanitize(orderNos);
        if (contracts.isEmpty() && orders.isEmpty()) {
            return RevokeCheckResult.ok();
        }
        List<CommissionApplication> consumed = selectConsumedApplications(null, contracts, orders);
        if (!consumed.isEmpty()) {
            return RevokeCheckResult.reject(
                "该批次涉及合同存在已提交/审批/锁定/驳回的结佣申请单（" + consumed.size()
                    + " 张），禁止撤销，请先在结佣模块作废结佣单");
        }
        return RevokeCheckResult.ok();
    }

    @Override
    public java.util.Map<String, String> findConsumedBizKeys(String period,
                                                             Collection<String> contractNos,
                                                             Collection<String> orderNos) {
        List<String> contracts = sanitize(contractNos);
        List<String> orders = sanitize(orderNos);
        if (contracts.isEmpty() && orders.isEmpty()) {
            return java.util.Map.of();
        }
        List<CommissionApplication> consumed = selectConsumedApplications(period, contracts, orders);
        java.util.Map<String, String> result = new java.util.LinkedHashMap<>();
        for (CommissionApplication app : consumed) {
            String reason = "结佣单" + app.getStatus() + "（" + app.getApplyNo() + "）";
            if (app.getContractNo() != null && !app.getContractNo().isBlank()) {
                result.putIfAbsent(KEY_CONTRACT + app.getContractNo().trim(), reason);
            }
            if (app.getOrderNo() != null && !app.getOrderNo().isBlank()) {
                result.putIfAbsent(KEY_ORDER + app.getOrderNo().trim(), reason);
            }
        }
        return result;
    }

    /**
     * 查已进入正式审批链路（或已锁定/已驳回）的结佣单。
     * <p>
     * period 为空时不过滤期间（撤销预检必须跨月拦截，见类注释）；非空时只看该归属月的
     * 结佣单，另保留 period 为空的历史 LOCKED 单（保守拦截）。
     * LOCKED 不判断 process_instance_id：实收审批通过后自动建 DRAFT → 提交 → 直落 LOCKED
     * 的链路无流程实例，但业务上已审批通过并锁定，必须拦截。
     */
    private List<CommissionApplication> selectConsumedApplications(String period,
                                                                   List<String> contracts,
                                                                   List<String> orders) {
        LambdaQueryWrapper<CommissionApplication> appQuery = new LambdaQueryWrapper<CommissionApplication>()
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
                        ApplicationStatus.SUBMITTED, ApplicationStatus.APPROVED)
                    .isNotNull(CommissionApplication::getProcessInstanceId))
                .or().eq(CommissionApplication::getStatus, ApplicationStatus.LOCKED)
                .or().eq(CommissionApplication::getStatus, ApplicationStatus.REJECTED));
        if (period != null && !period.isBlank()) {
            appQuery.and(w -> w.eq(CommissionApplication::getPeriod, period.trim())
                .or().isNull(CommissionApplication::getPeriod));
        }
        return applicationMapper.selectList(appQuery);
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
