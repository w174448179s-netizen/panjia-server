package com.panjia.ledger.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.panjia.ledger.domain.LedgerSubject;
import com.panjia.ledger.dto.DeptAmountDTO;
import com.panjia.ledger.dto.DeptRowDTO;
import com.panjia.ledger.dto.LedgerReportColumnVO;
import com.panjia.ledger.dto.LedgerReportRowVO;
import com.panjia.ledger.dto.LedgerReportVO;
import com.panjia.ledger.dto.SubjectDeptAmountDTO;
import com.panjia.ledger.mapper.LedgerEntryMapper;
import com.panjia.ledger.mapper.LedgerSubjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 部门收支分析报表。
 * <p>
 * 口径：
 * <ul>
 *   <li>部门列 = 全部末级部门；录在非末级部门的数据单列“未分配”。</li>
 *   <li>手工录入：生效期间与归属月相交即整笔计入（不按天折算），支出以负数展示。</li>
 *   <li>佣金收入：实收明细 ACTIVE + 实收申请 APPROVED，按明细部门（缺失时回退员工部门）汇总。</li>
 *   <li>工资：薪资格批次应发合计(gross)，按明细部门汇总，支出负数展示。</li>
 *   <li>父级科目只做分组不展示汇总值；底部输出合计营收/合计费用/营业利润。</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class LedgerReportService {

    private static final String UNALLOCATED_NAME = "未分配（非末级部门）";
    private static final Long UNALLOCATED_ID = -1L;

    private final LedgerEntryMapper entryMapper;
    private final LedgerSubjectMapper subjectMapper;

    @Transactional(readOnly = true)
    public LedgerReportVO report(String period) {
        YearMonth ym = LedgerEntryService.parsePeriod(period);
        LocalDate periodStart = ym.atDay(1);
        LocalDate periodEnd = ym.atEndOfMonth();

        List<LedgerSubject> subjects = loadSubjects();
        Map<Long, LedgerSubject> subjectMap = subjects.stream()
            .collect(Collectors.toMap(LedgerSubject::getId, s -> s, (a, b) -> a));

        List<DeptRowDTO> depts = entryMapper.selectActiveDepts();
        Map<Long, DeptRowDTO> deptMap = depts.stream()
            .collect(Collectors.toMap(DeptRowDTO::getDeptId, d -> d, (a, b) -> a));

        // 科目+部门金额（已按展示方向转换符号：支出为负）
        Map<Long, Map<Long, BigDecimal>> amounts = new HashMap<>();
        for (SubjectDeptAmountDTO row : entryMapper.sumEntriesByPeriod(periodStart, periodEnd)) {
            LedgerSubject subject = subjectMap.get(row.getSubjectId());
            if (subject != null) {
                addAmount(amounts, subject, row.getDeptId(), row.getAmount());
            }
        }
        for (LedgerSubject subject : subjects) {
            if (LedgerSubjectService.DATA_SOURCE_RECEIVED.equals(subject.getDataSource())) {
                for (DeptAmountDTO row : entryMapper.sumReceivedByDept(period, periodStart)) {
                    addAmount(amounts, subject, row.getDeptId(), row.getAmount());
                }
            } else if (LedgerSubjectService.DATA_SOURCE_PAYROLL.equals(subject.getDataSource())) {
                for (DeptAmountDTO row : entryMapper.sumPayrollGrossByDept(period, periodStart)) {
                    addAmount(amounts, subject, row.getDeptId(), row.getAmount());
                }
            }
        }

        // 部门列 = 末级部门
        List<DeptRowDTO> leafDepts = collectLeafDepts(depts);
        List<LedgerReportColumnVO> columns = leafDepts.stream()
            .map(d -> new LedgerReportColumnVO(d.getDeptId(), d.getDeptName()))
            .collect(Collectors.toList());
        Set<Long> leafIds = leafDepts.stream().map(DeptRowDTO::getDeptId).collect(Collectors.toSet());

        Map<Long, Integer> deptIndex = new HashMap<>();
        for (int i = 0; i < columns.size(); i++) {
            deptIndex.put(columns.get(i).getDeptId(), i);
        }

        boolean hasUnallocated = amounts.values().stream()
            .flatMap(m -> m.entrySet().stream())
            .anyMatch(e -> e.getValue() != null && e.getValue().signum() != 0
                && (e.getKey() == null || !leafIds.contains(e.getKey())));
        int unallocatedIndex = -1;
        if (hasUnallocated) {
            unallocatedIndex = columns.size();
            columns.add(new LedgerReportColumnVO(UNALLOCATED_ID, UNALLOCATED_NAME));
        }
        int columnCount = columns.size();

        // 各科目各列金额
        Map<Long, BigDecimal[]> valueMap = new HashMap<>();
        for (LedgerSubject subject : subjects) {
            Map<Long, BigDecimal> deptAmounts = amounts.get(subject.getId());
            BigDecimal[] values = zeroArray(columnCount);
            if (deptAmounts != null) {
                for (Map.Entry<Long, BigDecimal> e : deptAmounts.entrySet()) {
                    int idx = resolveIndex(e.getKey(), deptIndex, unallocatedIndex);
                    if (idx >= 0 && idx < columnCount) {
                        values[idx] = values[idx].add(e.getValue());
                    }
                }
            }
            valueMap.put(subject.getId(), values);
        }

        List<SubjectNode> incomeFlat = flattenNodes(buildNodes(LedgerSubjectService.DIRECTION_INCOME, subjects), subjectMap);
        List<SubjectNode> expenseFlat = flattenNodes(buildNodes(LedgerSubjectService.DIRECTION_EXPENSE, subjects), subjectMap);

        BigDecimal[] incomeTotals = zeroArray(columnCount);
        BigDecimal[] expenseTotals = zeroArray(columnCount);
        List<LedgerReportRowVO> rows = new ArrayList<>();
        appendSubjectRows(incomeFlat, valueMap, rows, incomeTotals);
        rows.add(totalRow("合计营收", incomeTotals, columnCount));
        appendSubjectRows(expenseFlat, valueMap, rows, expenseTotals);
        rows.add(totalRow("合计费用", expenseTotals, columnCount));
        BigDecimal[] profit = new BigDecimal[columnCount];
        for (int i = 0; i < columnCount; i++) {
            profit[i] = incomeTotals[i].add(expenseTotals[i]);
        }
        rows.add(totalRow("营业利润", profit, columnCount));

        LedgerReportVO vo = new LedgerReportVO();
        vo.setPeriod(period);
        vo.setRootDeptName(rootDeptName(depts, deptMap));
        vo.setColumns(columns);
        vo.setRows(rows);
        vo.setIncomeTotal(money(sum(incomeTotals)));
        vo.setExpenseTotal(money(sum(expenseTotals)));
        vo.setProfitTotal(money(vo.getIncomeTotal().add(vo.getExpenseTotal())));
        return vo;
    }

    private List<LedgerSubject> loadSubjects() {
        return subjectMapper.selectList(
            new LambdaQueryWrapper<LedgerSubject>()
                .eq(LedgerSubject::getStatus, "0")
                .orderByAsc(LedgerSubject::getSortOrder)
                .orderByAsc(LedgerSubject::getId));
    }

    private void addAmount(Map<Long, Map<Long, BigDecimal>> amounts, LedgerSubject subject,
                           Long deptId, BigDecimal amount) {
        if (amount == null || amount.signum() == 0) {
            return;
        }
        BigDecimal signed = LedgerSubjectService.DIRECTION_EXPENSE.equals(subject.getDirection())
            ? amount.negate() : amount;
        amounts.computeIfAbsent(subject.getId(), k -> new HashMap<>())
            .merge(deptId, signed, BigDecimal::add);
    }

    private int resolveIndex(Long deptId, Map<Long, Integer> deptIndex, int unallocatedIndex) {
        if (deptId == null) {
            return unallocatedIndex;
        }
        Integer idx = deptIndex.get(deptId);
        return idx == null ? unallocatedIndex : idx;
    }

    /** 递归收集末级部门（保持部门排序） */
    private List<DeptRowDTO> collectLeafDepts(List<DeptRowDTO> depts) {
        Map<Long, List<DeptRowDTO>> children = new HashMap<>();
        for (DeptRowDTO d : depts) {
            children.computeIfAbsent(d.getParentId(), k -> new ArrayList<>()).add(d);
        }
        Comparator<DeptRowDTO> cmp = Comparator
            .comparing((DeptRowDTO d) -> d.getOrderNum() == null ? 0 : d.getOrderNum())
            .thenComparing(DeptRowDTO::getDeptId);
        children.values().forEach(list -> list.sort(cmp));

        Map<Long, DeptRowDTO> deptMap = depts.stream()
            .collect(Collectors.toMap(DeptRowDTO::getDeptId, d -> d, (a, b) -> a));
        List<DeptRowDTO> roots = depts.stream()
            .filter(d -> d.getParentId() == null || d.getParentId() == 0L || !deptMap.containsKey(d.getParentId()))
            .sorted(cmp)
            .collect(Collectors.toList());

        List<DeptRowDTO> result = new ArrayList<>();
        Set<Long> visited = new HashSet<>();
        for (DeptRowDTO root : roots) {
            dfsDept(root, children, result, visited);
        }
        return result;
    }

    private void dfsDept(DeptRowDTO node, Map<Long, List<DeptRowDTO>> children,
                         List<DeptRowDTO> result, Set<Long> visited) {
        if (!visited.add(node.getDeptId())) {
            return;
        }
        List<DeptRowDTO> child = children.getOrDefault(node.getDeptId(), Collections.emptyList());
        if (child.isEmpty()) {
            result.add(node);
            return;
        }
        for (DeptRowDTO c : child) {
            dfsDept(c, children, result, visited);
        }
    }

    private String rootDeptName(List<DeptRowDTO> depts, Map<Long, DeptRowDTO> deptMap) {
        List<String> names = depts.stream()
            .filter(d -> d.getParentId() == null || d.getParentId() == 0L || !deptMap.containsKey(d.getParentId()))
            .map(DeptRowDTO::getDeptName)
            .collect(Collectors.toList());
        return names.isEmpty() ? "全部部门" : String.join("、", names);
    }

    // ==================== 科目树与合并跨度 ====================

    private List<SubjectNode> buildNodes(String direction, List<LedgerSubject> subjects) {
        Map<Long, LedgerSubject> byId = subjects.stream()
            .filter(s -> direction.equals(s.getDirection()))
            .collect(Collectors.toMap(LedgerSubject::getId, s -> s, (a, b) -> a));
        Map<Long, List<LedgerSubject>> children = new HashMap<>();
        for (LedgerSubject s : byId.values()) {
            Long pid = s.getParentId() == null ? 0L : s.getParentId();
            children.computeIfAbsent(pid, k -> new ArrayList<>()).add(s);
        }
        Comparator<LedgerSubject> cmp = Comparator
            .comparing((LedgerSubject s) -> s.getSortOrder() == null ? 0 : s.getSortOrder())
            .thenComparing(LedgerSubject::getId);
        children.values().forEach(list -> list.sort(cmp));

        List<SubjectNode> roots = new ArrayList<>();
        for (LedgerSubject s : byId.values()) {
            Long pid = s.getParentId() == null ? 0L : s.getParentId();
            if (pid == 0L) {
                roots.add(buildNode(s, children, new HashSet<>()));
            }
        }
        roots.sort(Comparator
            .comparing((SubjectNode n) -> n.subject.getSortOrder() == null ? 0 : n.subject.getSortOrder())
            .thenComparing(n -> n.subject.getId()));
        return roots;
    }

    private SubjectNode buildNode(LedgerSubject subject, Map<Long, List<LedgerSubject>> children, Set<Long> visiting) {
        SubjectNode node = new SubjectNode(subject);
        if (!visiting.add(subject.getId())) {
            return node;
        }
        for (LedgerSubject child : children.getOrDefault(subject.getId(), Collections.emptyList())) {
            node.children.add(buildNode(child, children, visiting));
        }
        visiting.remove(subject.getId());
        return node;
    }

    /** 先序展开，并计算每行的四级合并跨度 */
    private List<SubjectNode> flattenNodes(List<SubjectNode> roots, Map<Long, LedgerSubject> subjectMap) {
        List<SubjectNode> flat = new ArrayList<>();
        Map<Long, SubjectNode> nodeMap = new HashMap<>();
        for (SubjectNode root : roots) {
            flattenNode(root, flat, nodeMap);
        }
        for (int i = 0; i < flat.size(); i++) {
            flat.get(i).start = i;
        }
        for (SubjectNode node : flat) {
            node.size = countSize(node);
        }
        for (int i = 0; i < flat.size(); i++) {
            SubjectNode node = flat.get(i);
            node.spans = new int[4];
            List<LedgerSubject> path = subjectPath(node.subject, subjectMap);
            for (LedgerSubject ancestor : path) {
                SubjectNode ancestorNode = nodeMap.get(ancestor.getId());
                Integer level = ancestor.getLevel();
                if (ancestorNode != null && ancestorNode.start == i && level != null && level >= 1 && level <= 4) {
                    node.spans[level - 1] = ancestorNode.size;
                }
            }
        }
        return flat;
    }

    private void flattenNode(SubjectNode node, List<SubjectNode> flat, Map<Long, SubjectNode> nodeMap) {
        flat.add(node);
        nodeMap.put(node.subject.getId(), node);
        for (SubjectNode child : node.children) {
            flattenNode(child, flat, nodeMap);
        }
    }

    private int countSize(SubjectNode node) {
        int size = 1;
        for (SubjectNode child : node.children) {
            size += countSize(child);
        }
        return size;
    }

    private List<LedgerSubject> subjectPath(LedgerSubject subject, Map<Long, LedgerSubject> subjectMap) {
        List<LedgerSubject> path = new ArrayList<>();
        LedgerSubject cur = subject;
        int guard = 0;
        while (cur != null && guard++ < 10) {
            path.add(0, cur);
            if (cur.getParentId() == null || cur.getParentId() == 0L) {
                break;
            }
            cur = subjectMap.get(cur.getParentId());
        }
        return path;
    }

    private void appendSubjectRows(List<SubjectNode> nodes, Map<Long, BigDecimal[]> valueMap,
                                   List<LedgerReportRowVO> rows, BigDecimal[] directionTotals) {
        for (SubjectNode node : nodes) {
            LedgerSubject s = node.subject;
            LedgerReportRowVO row = new LedgerReportRowVO();
            row.setSubjectId(s.getId());
            row.setSubjectName(s.getSubjectName());
            row.setLevel(s.getLevel());
            row.setDirection(s.getDirection());
            row.setDataSource(s.getDataSource());
            row.setTotalRow(false);
            row.setSpans(node.spans == null ? new int[4] : node.spans);
            if (node.children.isEmpty()) {
                BigDecimal[] values = valueMap.getOrDefault(s.getId(), zeroArray(directionTotals.length));
                row.setValues(toList(values));
                row.setTotal(money(sum(values)));
                for (int i = 0; i < directionTotals.length && i < values.length; i++) {
                    directionTotals[i] = directionTotals[i].add(values[i]);
                }
            }
            rows.add(row);
        }
    }

    private LedgerReportRowVO totalRow(String label, BigDecimal[] values, int columnCount) {
        LedgerReportRowVO row = new LedgerReportRowVO();
        row.setTotalRow(true);
        row.setTotalLabel(label);
        row.setLevel(0);
        row.setSpans(new int[4]);
        BigDecimal[] scaled = new BigDecimal[columnCount];
        for (int i = 0; i < columnCount; i++) {
            scaled[i] = money(values[i]);
        }
        row.setValues(toList(scaled));
        row.setTotal(money(sum(scaled)));
        return row;
    }

    private List<BigDecimal> toList(BigDecimal[] values) {
        List<BigDecimal> list = new ArrayList<>(values.length);
        for (BigDecimal v : values) {
            list.add(money(v));
        }
        return list;
    }

    private BigDecimal[] zeroArray(int size) {
        BigDecimal[] arr = new BigDecimal[size];
        Arrays.fill(arr, BigDecimal.ZERO);
        return arr;
    }

    private BigDecimal money(BigDecimal v) {
        return (v == null ? BigDecimal.ZERO : v).setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal sum(BigDecimal[] values) {
        BigDecimal total = BigDecimal.ZERO;
        for (BigDecimal v : values) {
            total = total.add(v == null ? BigDecimal.ZERO : v);
        }
        return total;
    }

    private static class SubjectNode {
        private final LedgerSubject subject;
        private final List<SubjectNode> children = new ArrayList<>();
        private int start;
        private int size;
        private int[] spans;

        SubjectNode(LedgerSubject subject) {
            this.subject = subject;
        }
    }
}
