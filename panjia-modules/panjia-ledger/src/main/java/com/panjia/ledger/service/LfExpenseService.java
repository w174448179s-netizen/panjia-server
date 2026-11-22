package com.panjia.ledger.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.panjia.ledger.domain.LedgerEntry;
import com.panjia.ledger.domain.LedgerSubject;
import com.panjia.ledger.domain.LfStatementRow;
import com.panjia.ledger.dto.DeptRowDTO;
import com.panjia.ledger.mapper.LedgerEntryMapper;
import com.panjia.ledger.mapper.LedgerSubjectMapper;
import com.panjia.ledger.mapper.LfStatementRowMapper;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.utils.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 理房通流水费用归集：科目匹配、组归属、写入部门收支录入。
 */
@Service
@RequiredArgsConstructor
public class LfExpenseService {

    private static final String SOURCE_TYPE = "LF_STATEMENT";
    private static final String DEPARTMENT_INCOME_SUBJECT = "品效商圈权益";

    private final LedgerSubjectMapper subjectMapper;
    private final LedgerEntryMapper entryMapper;
    private final LfStatementRowMapper rowMapper;

    /** 启用的末级手工支出科目（费用只能归到这些科目） */
    public List<LedgerSubject> loadExpenseSubjects() {
        List<LedgerSubject> all = subjectMapper.selectList(new LambdaQueryWrapper<LedgerSubject>()
            .eq(LedgerSubject::getDirection, "EXPENSE")
            .eq(LedgerSubject::getStatus, "0"));
        Set<Long> parentIds = all.stream().map(LedgerSubject::getParentId)
            .filter(id -> id != null && id != 0L).collect(Collectors.toSet());
        return all.stream()
            .filter(s -> !parentIds.contains(s.getId()))
            .filter(s -> StringUtils.isBlank(s.getDataSource()) || "MANUAL".equals(s.getDataSource()))
            .collect(Collectors.toList());
    }

    public List<DeptRowDTO> loadSubtree(Long storeDeptId) {
        return rowMapper.selectDeptSubtree(storeDeptId);
    }

    /** 子树末级部门 */
    public List<Long> leafDeptIds(List<DeptRowDTO> subtree) {
        if (subtree == null || subtree.isEmpty()) {
            return List.of();
        }
        Set<Long> parentIds = subtree.stream().map(DeptRowDTO::getParentId)
            .filter(id -> id != null).collect(Collectors.toSet());
        return subtree.stream().map(DeptRowDTO::getDeptId)
            .filter(id -> !parentIds.contains(id))
            .collect(Collectors.toList());
    }

    public boolean isLeafUnderStore(Long storeDeptId, Long deptId) {
        if (storeDeptId == null || deptId == null) {
            return false;
        }
        return leafDeptIds(loadSubtree(storeDeptId)).contains(deptId);
    }

    /**
     * 给费用行匹配科目和归属组，部门收入固定归入导入门店。
     * <p>费用行：单组门店自动归到该组；多组门店为 PENDING，等待手工划归。</p>
     */
    public void applyExpenseMeta(LfStatementRow row, List<LedgerSubject> subjects, List<DeptRowDTO> subtree) {
        if ("DEPT_INCOME".equals(row.getCategory())) {
            row.setSubjectId(findDepartmentIncomeSubject());
            row.setExpenseDeptId(row.getStoreDeptId());
            row.setAllocStatus("AUTO");
            return;
        }
        if (!"FEE".equals(row.getCategory())) {
            row.setSubjectId(null);
            row.setExpenseDeptId(null);
            row.setAllocStatus(null);
            return;
        }
        row.setSubjectId(matchSubject(row, subjects));

        List<Long> leaves = leafDeptIds(subtree);
        if (leaves.size() == 1) {
            row.setExpenseDeptId(leaves.get(0));
            row.setAllocStatus("AUTO");
        } else if (leaves.isEmpty()) {
            row.setExpenseDeptId(row.getStoreDeptId());
            row.setAllocStatus("AUTO");
        } else if ("MANUAL".equals(row.getAllocStatus()) && row.getExpenseDeptId() != null) {
            // 保留手工划归
        } else {
            row.setExpenseDeptId(null);
            row.setAllocStatus("PENDING");
        }
    }

    private Long findDepartmentIncomeSubject() {
        LedgerSubject subject = subjectMapper.selectOne(new LambdaQueryWrapper<LedgerSubject>()
            .eq(LedgerSubject::getDirection, "INCOME")
            .eq(LedgerSubject::getSubjectName, DEPARTMENT_INCOME_SUBJECT)
            .eq(LedgerSubject::getStatus, "0")
            .last("LIMIT 1"));
        return subject == null ? null : subject.getId();
    }

    private Long matchSubject(LfStatementRow row, List<LedgerSubject> subjects) {
        String desc = normalize(row.getBusinessDesc());
        String remark = normalize(row.getTransactionRemark());
        String type = normalize(row.getTransactionType());
        for (LedgerSubject s : subjects) {
            String name = normalize(s.getSubjectName());
            if (name.isEmpty()) {
                continue;
            }
            if (name.equals(desc) || name.equals(remark) || name.equals(type)) {
                return s.getId();
            }
        }
        for (LedgerSubject s : subjects) {
            String name = normalize(s.getSubjectName());
            if (!name.isEmpty() && (desc.contains(name) || remark.contains(name) || name.contains(desc) && !desc.isEmpty())) {
                return s.getId();
            }
        }
        return null;
    }

    private String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value.replace(" ", "").replace("　", "").trim();
    }

    /** 单行费用/部门收入写入或覆盖部门收支（按记账流水号幂等） */
    @Transactional(rollbackFor = Exception.class)
    public void syncLedgerEntry(LfStatementRow row, Long operatorId) {
        removeLedgerEntry(row.getVoucherNo());
        boolean expense = "FEE".equals(row.getCategory());
        boolean income = "DEPT_INCOME".equals(row.getCategory());
        Long deptId = income ? row.getStoreDeptId() : row.getExpenseDeptId();
        if ((!expense && !income) || row.getSubjectId() == null
            || deptId == null || row.getTransactionAmount() == null) {
            return;
        }
        BigDecimal amount = income ? row.getTransactionAmount() : row.getTransactionAmount().abs();
        if (amount.compareTo(BigDecimal.ZERO) == 0) {
            return;
        }
        YearMonth ym = YearMonth.parse(row.getPeriod());
        LedgerEntry entry = new LedgerEntry();
        entry.setSubjectId(row.getSubjectId());
        entry.setDeptId(deptId);
        entry.setDirection(income ? "INCOME" : "EXPENSE");
        entry.setAmount(amount);
        entry.setStartDate(ym.atDay(1));
        entry.setEndDate(ym.atEndOfMonth());
        entry.setRemark(buildRemark(row));
        entry.setSourceType(SOURCE_TYPE);
        entry.setSourceSerialNo(row.getVoucherNo());
        entry.setCreateBy(operatorId);
        entry.setCreateTime(java.time.LocalDateTime.now());
        entry.setUpdateTime(java.time.LocalDateTime.now());
        entryMapper.insert(entry);
    }

    private String buildRemark(LfStatementRow row) {
        StringBuilder sb = new StringBuilder("理房通流水");
        if (StringUtils.isNotBlank(row.getBusinessDesc())) {
            sb.append(" · ").append(row.getBusinessDesc());
        }
        if (StringUtils.isNotBlank(row.getTransactionRemark())) {
            sb.append(" · ").append(row.getTransactionRemark());
        }
        if (StringUtils.isNotBlank(row.getCounterpartyName())) {
            sb.append(" · ").append(row.getCounterpartyName());
        }
        String remark = sb.toString();
        return remark.length() > 500 ? remark.substring(0, 500) : remark;
    }

    public void removeLedgerEntry(String voucherNo) {
        if (StringUtils.isBlank(voucherNo)) {
            return;
        }
        LedgerEntry existing = entryMapper.selectOne(new LambdaQueryWrapper<LedgerEntry>()
            .eq(LedgerEntry::getSourceType, SOURCE_TYPE)
            .eq(LedgerEntry::getSourceSerialNo, voucherNo)
            .last("LIMIT 1"));
        if (existing != null) {
            entryMapper.deleteById(existing.getId());
        }
    }

    /** 批次内已匹配科目和组的费用统一同步（重复执行按流水号覆盖） */
    @Transactional(rollbackFor = Exception.class)
    public void syncBatch(Long batchId, Long operatorId) {
        for (LfStatementRow row : rowMapper.selectExpenseRowsForLedger(batchId)) {
            syncLedgerEntry(row, operatorId);
        }
    }

    /** 手工划归后同步指定费用行 */
    @Transactional(rollbackFor = Exception.class)
    public void syncRows(List<LfStatementRow> rows, Long operatorId) {
        for (LfStatementRow row : rows) {
            syncLedgerEntry(row, operatorId);
        }
    }

    /** 科目新增后：把待补科目的历史流水重新匹配并同步 */
    @Transactional(rollbackFor = Exception.class)
    public int rematchPendingSubjects(Long storeDeptId, Long operatorId) {
        List<LedgerSubject> subjects = loadExpenseSubjects();
        List<LfStatementRow> pending = rowMapper.selectList(new LambdaQueryWrapper<LfStatementRow>()
            .eq(LfStatementRow::getCategory, "FEE")
            .isNull(LfStatementRow::getSubjectId)
            .eq(storeDeptId != null, LfStatementRow::getStoreDeptId, storeDeptId));
        Map<Long, List<DeptRowDTO>> subtrees = new HashMap<>();
        int count = 0;
        for (LfStatementRow row : pending) {
            List<DeptRowDTO> subtree = subtrees.computeIfAbsent(row.getStoreDeptId(),
                this::loadSubtree);
            applyExpenseMeta(row, subjects, subtree);
            if (row.getSubjectId() != null) {
                row.setUpdateTime(java.time.LocalDateTime.now());
                rowMapper.updateById(row);
                syncLedgerEntry(row, operatorId);
                count++;
            }
        }
        return count;
    }

    public Map<Long, String> subjectNames() {
        return subjectMapper.selectList(null).stream()
            .collect(Collectors.toMap(LedgerSubject::getId, LedgerSubject::getSubjectName, (a, b) -> a));
    }

    public Map<Long, String> deptNames() {
        Map<Long, String> result = new HashMap<>();
        for (DeptRowDTO d : entryMapper.selectActiveDepts()) {
            result.put(d.getDeptId(), d.getDeptName());
        }
        return result;
    }

    public Set<Long> expenseLeafIds() {
        return new HashSet<>(loadExpenseSubjects().stream().map(LedgerSubject::getId).toList());
    }

    /** 校验划归目标必须是门店下的末级组 */
    public void validateAllocTarget(LfStatementRow row, Long deptId) {
        if (!"FEE".equals(row.getCategory())) {
            throw new org.dromara.common.core.exception.ServiceException("只有费用类支出可以划归");
        }
        if (!isLeafUnderStore(row.getStoreDeptId(), deptId)) {
            throw new org.dromara.common.core.exception.ServiceException("划归目标必须是该门店下的末级组");
        }
    }

    /** 用于报表/统计的期间起止 */
    public LocalDate periodStart(String period) {
        return YearMonth.parse(period).atDay(1);
    }
}
