package com.panjia.ledger.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.panjia.ledger.domain.LfAccount;
import com.panjia.ledger.domain.LfMatchResult;
import com.panjia.ledger.domain.LfStatementRow;
import com.panjia.ledger.dto.LfAllocBo;
import com.panjia.ledger.dto.LfDiffDetailVO;
import com.panjia.ledger.dto.LfStatementQuery;
import com.panjia.ledger.dto.LfStatementVO;
import com.panjia.ledger.mapper.LfAccountMapper;
import com.panjia.ledger.mapper.LfMatchResultMapper;
import com.panjia.ledger.mapper.LfStatementRowMapper;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.PageResult;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 理房通流水查询、费用划归与差异报表。
 */
@Service
@RequiredArgsConstructor
public class LfStatementQueryService {

    private final LfStatementRowMapper rowMapper;
    private final LfMatchResultMapper matchResultMapper;
    private final LfAccountMapper accountMapper;
    private final LfExpenseService expenseService;

    public PageResult<LfStatementVO> list(LfStatementQuery query, PageQuery pageQuery,
                                          Long loginDeptId, boolean allScope) {
        LambdaQueryWrapper<LfStatementRow> wrapper = buildWrapper(query, loginDeptId, allScope);
        if (wrapper == null) {
            return PageResult.build(List.of(), 0);
        }
        Page<LfStatementRow> page = rowMapper.selectPage(
            pageQuery == null ? new PageQuery().build() : pageQuery.build(), wrapper);
        return PageResult.build(toVOs(page.getRecords()), page.getTotal());
    }

    /** 导出等不分页场景 */
    public List<LfStatementVO> listAll(LfStatementQuery query, Long loginDeptId, boolean allScope) {
        LambdaQueryWrapper<LfStatementRow> wrapper = buildWrapper(query, loginDeptId, allScope);
        if (wrapper == null) {
            return List.of();
        }
        return toVOs(rowMapper.selectList(wrapper));
    }

    private LambdaQueryWrapper<LfStatementRow> buildWrapper(LfStatementQuery query, Long loginDeptId,
                                                            boolean allScope) {
        LambdaQueryWrapper<LfStatementRow> wrapper = new LambdaQueryWrapper<>();
        if (query != null) {
            wrapper.eq(StringUtils.isNotBlank(query.getPeriod()), LfStatementRow::getPeriod, query.getPeriod())
                .eq(query.getStoreDeptId() != null, LfStatementRow::getStoreDeptId, query.getStoreDeptId())
                .eq(query.getAccountId() != null, LfStatementRow::getAccountId, query.getAccountId())
                .eq(StringUtils.isNotBlank(query.getIncomeExpenseType()),
                    LfStatementRow::getIncomeExpenseType, query.getIncomeExpenseType())
                .like(StringUtils.isNotBlank(query.getTransactionType()),
                    LfStatementRow::getTransactionType, query.getTransactionType())
                .eq(StringUtils.isNotBlank(query.getCategory()),
                    LfStatementRow::getCategory, query.getCategory())
                .eq(query.getDecorationFlag() != null,
                    LfStatementRow::getDecorationFlag, query.getDecorationFlag())
                .eq(StringUtils.isNotBlank(query.getAllocStatus()),
                    LfStatementRow::getAllocStatus, query.getAllocStatus())
                .eq(StringUtils.isNotBlank(query.getMatchStatus()),
                    LfStatementRow::getMatchStatus, query.getMatchStatus())
                .eq(StringUtils.isNotBlank(query.getContractNo()),
                    LfStatementRow::getContractNo, query.getContractNo())
                .like(StringUtils.isNotBlank(query.getBusinessDesc()),
                    LfStatementRow::getBusinessDesc, query.getBusinessDesc())
                .like(StringUtils.isNotBlank(query.getTransactionRemark()),
                    LfStatementRow::getTransactionRemark, query.getTransactionRemark());
            if (StringUtils.isNotBlank(query.getKeyword())) {
                wrapper.and(w -> w.like(LfStatementRow::getContractNo, query.getKeyword())
                    .or().like(LfStatementRow::getBusinessDesc, query.getKeyword())
                    .or().like(LfStatementRow::getTransactionRemark, query.getKeyword())
                    .or().like(LfStatementRow::getCounterpartyName, query.getKeyword()));
            }
        }
        if (!allScope) {
            if (loginDeptId == null) {
                return null;
            }
            List<Long> deptIds = new java.util.ArrayList<>(expenseService.loadSubtree(loginDeptId).stream()
                .map(com.panjia.ledger.dto.DeptRowDTO::getDeptId).toList());
            deptIds.addAll(rowMapper.selectAncestorDeptIds(loginDeptId));
            deptIds = deptIds.stream().distinct().toList();
            if (deptIds.isEmpty()) {
                return null;
            }
            wrapper.in(LfStatementRow::getStoreDeptId, deptIds);
        }
        wrapper.orderByDesc(LfStatementRow::getTransactionTime).orderByDesc(LfStatementRow::getId);
        return wrapper;
    }

    private List<LfStatementVO> toVOs(List<LfStatementRow> rows) {
        Map<Long, String> subjectNames = expenseService.subjectNames();
        Map<Long, String> deptNames = expenseService.deptNames();
        Map<Long, LfAccount> accounts = accountMapper.selectList(null).stream()
            .collect(Collectors.toMap(LfAccount::getId, a -> a, (a, b) -> a));
        List<LfStatementVO> result = new ArrayList<>(rows.size());
        for (LfStatementRow row : rows) {
            LfStatementVO vo = new LfStatementVO();
            BeanUtils.copyProperties(row, vo);
            LfAccount account = accounts.get(row.getAccountId());
            vo.setAccountName(account == null ? null : account.getAccountName());
            vo.setSubjectName(subjectNames.get(row.getSubjectId()));
            vo.setExpenseDeptName(deptNames.get(row.getExpenseDeptId()));
            result.add(vo);
        }
        return result;
    }

    /** 费用划归：整笔划到一个末级组；划归后立即写入部门收支 */
    @Transactional(rollbackFor = Exception.class)
    public int allocate(LfAllocBo bo, Long operatorId) {
        if (bo == null || bo.getIds() == null || bo.getIds().isEmpty() || bo.getExpenseDeptId() == null) {
            throw new ServiceException("请选择费用行和归属组");
        }
        int count = 0;
        for (Long id : bo.getIds()) {
            LfStatementRow row = rowMapper.selectById(id);
            if (row == null) {
                continue;
            }
            expenseService.validateAllocTarget(row, bo.getExpenseDeptId());
            row.setExpenseDeptId(bo.getExpenseDeptId());
            row.setAllocStatus("MANUAL");
            row.setAllocBy(operatorId);
            row.setAllocTime(LocalDateTime.now());
            row.setUpdateTime(LocalDateTime.now());
            rowMapper.updateById(row);
            expenseService.syncLedgerEntry(row, operatorId);
            count++;
        }
        return count;
    }

    public PageResult<LfMatchResult> diffList(String matchStatus, String contractNo, PageQuery pageQuery) {
        LambdaQueryWrapper<LfMatchResult> wrapper = new LambdaQueryWrapper<LfMatchResult>()
            .eq(StringUtils.isNotBlank(matchStatus), LfMatchResult::getMatchStatus, matchStatus)
            .like(StringUtils.isNotBlank(contractNo), LfMatchResult::getContractNo, contractNo)
            .orderByDesc(LfMatchResult::getLastMatchTime)
            .orderByDesc(LfMatchResult::getId);
        Page<LfMatchResult> page = matchResultMapper.selectPage(
            pageQuery == null ? new PageQuery().build() : pageQuery.build(), wrapper);
        return PageResult.build(page.getRecords(), page.getTotal());
    }

    public LfDiffDetailVO diffDetail(String contractNo) {
        if (StringUtils.isBlank(contractNo)) {
            throw new ServiceException("合同号不能为空");
        }
        LfMatchResult result = matchResultMapper.selectOne(new LambdaQueryWrapper<LfMatchResult>()
            .eq(LfMatchResult::getContractNo, contractNo).last("LIMIT 1"));
        LfDiffDetailVO vo = new LfDiffDetailVO();
        vo.setContractNo(contractNo);
        vo.setFlowAmount(result == null ? BigDecimal.ZERO : result.getFlowAmount());
        vo.setReceivedAmount(result == null ? BigDecimal.ZERO : result.getReceivedAmount());
        vo.setDiffAmount(result == null ? BigDecimal.ZERO : result.getDiffAmount());
        vo.setMatchStatus(result == null ? null : result.getMatchStatus());
        for (Map<String, Object> line : rowMapper.selectFlowLines(contractNo)) {
            LfDiffDetailVO.FlowLine flow = new LfDiffDetailVO.FlowLine();
            flow.setId(asLong(line.get("id")));
            flow.setPeriod(asString(line.get("period")));
            flow.setStoreName(asString(line.get("store_name")));
            flow.setTransactionTime(asDateTime(line.get("transaction_time")));
            flow.setIncomeExpenseType(asString(line.get("income_expense_type")));
            flow.setBusinessDesc(asString(line.get("business_desc")));
            flow.setTransactionRemark(asString(line.get("transaction_remark")));
            flow.setTransactionAmount(asDecimal(line.get("transaction_amount")));
            flow.setVoucherNo(asString(line.get("voucher_no")));
            vo.getFlowLines().add(flow);
        }
        for (Map<String, Object> line : rowMapper.selectReceivedLines(contractNo)) {
            LfDiffDetailVO.ReceivedLine received = new LfDiffDetailVO.ReceivedLine();
            received.setId(asLong(line.get("id")));
            received.setPeriod(asString(line.get("period")));
            received.setEmployeeId(asLong(line.get("employeeId")));
            received.setRoleName(asString(line.get("roleName")));
            received.setRoleType(asString(line.get("roleType")));
            received.setPerformanceAmount(asDecimal(line.get("performanceAmount")));
            received.setSourceType(asString(line.get("sourceType")));
            received.setApplyStatus(asString(line.get("applyStatus")));
            vo.getReceivedLines().add(received);
        }
        return vo;
    }

    public int rematchPendingSubjects(Long storeDeptId, Long operatorId) {
        return expenseService.rematchPendingSubjects(storeDeptId, operatorId);
    }

    public PageResult<Map<String, Object>> pendingSubjects(Long storeDeptId, PageQuery pageQuery) {
        Page<Map<String, Object>> page = rowMapper.selectPendingSubjectSummary(
            pageQuery == null ? new PageQuery().build() : pageQuery.build(), storeDeptId);
        return PageResult.build(page.getRecords(), page.getTotal());
    }

    private Long asLong(Object value) {
        if (value == null) {
            return null;
        }
        return value instanceof Number n ? n.longValue() : Long.valueOf(value.toString());
    }

    private String asString(Object value) {
        return value == null ? null : value.toString();
    }

    private BigDecimal asDecimal(Object value) {
        if (value == null) {
            return BigDecimal.ZERO;
        }
        return value instanceof BigDecimal d ? d : new BigDecimal(value.toString());
    }

    private LocalDateTime asDateTime(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof LocalDateTime time) {
            return time;
        }
        if (value instanceof java.sql.Timestamp timestamp) {
            return timestamp.toLocalDateTime();
        }
        return LocalDateTime.parse(value.toString().replace(" ", "T"));
    }
}
