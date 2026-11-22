package com.panjia.ledger.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.panjia.ledger.domain.LfMatchResult;
import com.panjia.ledger.domain.LfStatementRow;
import com.panjia.ledger.mapper.LfMatchResultMapper;
import com.panjia.ledger.mapper.LfStatementRowMapper;
import com.panjia.received.domain.bo.LfStatementReceivedBo;
import com.panjia.received.service.IReceivedApplyService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.utils.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 理房通流水匹配：按合同号汇总全部流水与系统全部实收。
 * <p>导入后自动执行；去化模式在此生成实收并提交审批。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LfMatchService {

    private static final BigDecimal MATCH_TOLERANCE = new BigDecimal("0.10");

    private final LfStatementRowMapper rowMapper;
    private final LfMatchResultMapper matchResultMapper;
    private final IReceivedApplyService receivedApplyService;

    @Data
    public static class Stats {
        private int contractCount;
        private int matched;
        private int diff;
        private int notFound;
        private int pendingSignRole;
    }

    @Transactional(rollbackFor = Exception.class)
    public Stats matchContracts(Collection<String> contractNos, Long operatorId) {
        Stats stats = new Stats();
        if (contractNos == null || contractNos.isEmpty()) {
            return stats;
        }
        for (String contractNo : contractNos.stream().filter(StringUtils::isNotBlank).distinct().toList()) {
            stats.contractCount++;
            boolean decoration = matchDecoration(contractNo, operatorId, stats);
            boolean normal = matchNormal(contractNo, stats);
            if (!normal && !decoration) {
                // 拆单流水改归其他合同后，原合同可能已无任何流水，不能保留历史匹配结果
                matchResultMapper.delete(new LambdaQueryWrapper<LfMatchResult>()
                    .eq(LfMatchResult::getContractNo, contractNo));
                stats.notFound++;
            }
        }
        return stats;
    }

    /** 去化模式：按归属月汇总美化收入，按新签角色人分摊，生成实收 */
    private boolean matchDecoration(String contractNo, Long operatorId, Stats stats) {
        List<LfStatementRow> rows = rowMapper.selectList(new LambdaQueryWrapper<LfStatementRow>()
            .eq(LfStatementRow::getContractNo, contractNo)
            .eq(LfStatementRow::getDecorationFlag, true)
            .in(LfStatementRow::getCategory, "BUSINESS", "REVERSAL"));
        if (rows.isEmpty()) {
            return false;
        }
        List<Map<String, Object>> roles = rowMapper.selectDecorationRoles(contractNo);
        if (roles.isEmpty()) {
            rowMapper.updateDecorationMatch(contractNo, "PENDING_SIGN_ROLE");
            stats.pendingSignRole++;
            return true;
        }
        Map<String, List<LfStatementRow>> byPeriod = rows.stream()
            .collect(Collectors.groupingBy(LfStatementRow::getPeriod, LinkedHashMap::new, Collectors.toList()));
        List<LfStatementReceivedBo> items = new ArrayList<>();
        for (Map.Entry<String, List<LfStatementRow>> entry : byPeriod.entrySet()) {
            LfStatementRow first = entry.getValue().get(0);
            BigDecimal amount = entry.getValue().stream()
                .map(LfStatementRow::getTransactionAmount)
                .filter(java.util.Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
            LfStatementReceivedBo bo = buildReceivedBo(contractNo, first, entry.getKey(), amount, roles);
            if (!bo.getRoles().isEmpty()) {
                items.add(bo);
            }
        }
        if (!items.isEmpty()) {
            receivedApplyService.createFromLfStatement(items, operatorId);
        }
        rowMapper.updateDecorationMatch(contractNo, "MATCHED");
        upsertResult(contractNo, sumDecorationFlow(rows),
            nvl(rowMapper.sumReceivedByContract(contractNo)), "MATCHED", rows.size());
        return true;
    }

    private BigDecimal sumDecorationFlow(List<LfStatementRow> rows) {
        return rows.stream().map(LfStatementRow::getTransactionAmount)
            .filter(java.util.Objects::nonNull)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** 普通业务流水：合同全量流水 vs 系统全部实收 */
    private boolean matchNormal(String contractNo, Stats stats) {
        int count = rowMapper.countFlowByContract(contractNo);
        if (count == 0) {
            return false;
        }
        BigDecimal flow = nvl(rowMapper.sumFlowByContract(contractNo));
        BigDecimal received = nvl(rowMapper.sumReceivedByContract(contractNo));
        BigDecimal diff = flow.subtract(received);
        String status;
        if (received.compareTo(BigDecimal.ZERO) == 0) {
            status = "NOT_FOUND";
            stats.notFound++;
        } else if (diff.abs().compareTo(BigDecimal.ZERO) == 0) {
            status = "MATCHED";
            stats.matched++;
        } else if (diff.abs().compareTo(MATCH_TOLERANCE) <= 0) {
            status = "TOLERANCE_MATCH";
            stats.matched++;
        } else {
            status = "AMOUNT_DIFF";
            stats.diff++;
        }
        rowMapper.updateMatchByContract(contractNo, status, diff);
        upsertResult(contractNo, flow, received, status, count);
        return true;
    }

    private void upsertResult(String contractNo, BigDecimal flow, BigDecimal received,
                              String status, int rowCount) {
        LfMatchResult result = matchResultMapper.selectOne(new LambdaQueryWrapper<LfMatchResult>()
            .eq(LfMatchResult::getContractNo, contractNo).last("LIMIT 1"));
        BigDecimal diff = flow.subtract(received);
        if (result == null) {
            result = new LfMatchResult();
            result.setContractNo(contractNo);
            result.setCreateTime(LocalDateTime.now());
        }
        result.setFlowAmount(flow);
        result.setReceivedAmount(received);
        result.setDiffAmount(diff);
        result.setMatchStatus(status);
        result.setFlowRowCount(rowCount);
        result.setLastMatchTime(LocalDateTime.now());
        result.setUpdateTime(LocalDateTime.now());
        if (result.getId() == null) {
            matchResultMapper.insert(result);
        } else {
            matchResultMapper.updateById(result);
        }
    }

    /**
     * 按新签角色人权重分摊一笔美化收入。
     * <p>权重优先取新签业绩金额占比，金额缺失时回退 shareRatio；尾差给最后一个角色。</p>
     */
    private LfStatementReceivedBo buildReceivedBo(String contractNo, LfStatementRow first, String period,
                                                  BigDecimal amount, List<Map<String, Object>> roles) {
        LfStatementReceivedBo bo = new LfStatementReceivedBo();
        bo.setStoreDeptId(first.getStoreDeptId());
        bo.setStoreName(first.getStoreName());
        bo.setContractNo(contractNo);
        bo.setPeriod(period);
        bo.setBusinessDate(first.getTransactionTime());
        bo.setPropertyAddress(first.getAddress());
        bo.setContractAmount(nvl(rowMapper.sumDecorationExpected(contractNo)));

        BigDecimal totalWeight = BigDecimal.ZERO;
        for (Map<String, Object> role : roles) {
            BigDecimal weight = amountWeight(role);
            if (weight.signum() > 0) {
                totalWeight = totalWeight.add(weight);
            }
        }
        if (totalWeight.signum() <= 0) {
            totalWeight = BigDecimal.valueOf(roles.size());
        }

        BigDecimal allocated = BigDecimal.ZERO;
        for (int i = 0; i < roles.size(); i++) {
            Map<String, Object> role = roles.get(i);
            BigDecimal weight = amountWeight(role);
            if (weight.signum() <= 0) {
                weight = BigDecimal.ONE;
            }
            BigDecimal share = i == roles.size() - 1
                ? amount.subtract(allocated)
                : amount.multiply(weight).divide(totalWeight, 2, RoundingMode.HALF_UP);
            allocated = allocated.add(share);
            if (share.signum() == 0) {
                continue;
            }
            LfStatementReceivedBo.RoleItem item = new LfStatementReceivedBo.RoleItem();
            item.setEmployeeId(asLong(role.get("employeeId")));
            item.setEmployeeExternalCode(asString(role.get("employeeExternalCode")));
            item.setDeptId(asLong(role.get("deptId")) != null ? asLong(role.get("deptId")) : first.getStoreDeptId());
            item.setRoleType(asString(role.get("roleType")));
            item.setRoleName(asString(role.get("roleName")));
            item.setShareRatio(asDecimal(role.get("shareRatio")));
            item.setAmount(share);
            bo.getRoles().add(item);
            if (StringUtils.isBlank(bo.getOrderNo())) {
                String orderNo = asString(role.get("orderNo"));
                bo.setOrderNo(StringUtils.isNotBlank(orderNo) ? orderNo : contractNo);
            }
        }
        return bo;
    }

    private BigDecimal amountWeight(Map<String, Object> role) {
        BigDecimal amount = asDecimal(role.get("performanceAmount")).abs();
        if (amount.signum() > 0) {
            return amount;
        }
        BigDecimal ratio = asDecimal(role.get("shareRatio")).abs();
        return ratio.signum() > 0 ? ratio : BigDecimal.ONE;
    }

    private BigDecimal nvl(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private Long asLong(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.valueOf(value.toString());
        } catch (Exception e) {
            return null;
        }
    }

    private String asString(Object value) {
        return value == null ? null : value.toString();
    }

    private BigDecimal asDecimal(Object value) {
        if (value == null) {
            return BigDecimal.ZERO;
        }
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof Number number) {
            return new BigDecimal(number.toString());
        }
        try {
            return new BigDecimal(value.toString());
        } catch (Exception e) {
            return BigDecimal.ZERO;
        }
    }
}
