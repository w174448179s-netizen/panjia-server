package com.panjia.ledger.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.panjia.ledger.domain.LedgerEntry;
import com.panjia.ledger.domain.LedgerSubject;
import com.panjia.ledger.dto.DeptRowDTO;
import com.panjia.ledger.dto.LedgerEntryExcelRow;
import com.panjia.ledger.dto.LedgerEntryVO;
import com.panjia.ledger.dto.LedgerImportResultVO;
import com.panjia.ledger.mapper.LedgerEntryMapper;
import com.panjia.ledger.mapper.LedgerSubjectMapper;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.StringUtils;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 部门收支录入服务。
 * <p>录入只允许发生在科目叶子节点与部门叶子节点；后台统一保存生效/失效日期（天）。</p>
 */
@Service
@RequiredArgsConstructor
public class LedgerEntryService {

    private final LedgerEntryMapper entryMapper;
    private final LedgerSubjectMapper subjectMapper;
    private final LedgerSubjectService subjectService;

    /**
     * 查询某归属月生效的录入明细（生效期间与归属月相交即返回）。
     * 前端用返回的 startDate/endDate 展示实际期间。
     */
    public List<LedgerEntryVO> list(String period, String direction, Long deptId, Long subjectId) {
        LambdaQueryWrapper<LedgerEntry> wrapper = new LambdaQueryWrapper<>();
        if (StringUtils.isNotBlank(period)) {
            YearMonth ym = parsePeriod(period);
            wrapper.le(LedgerEntry::getStartDate, ym.atEndOfMonth())
                .ge(LedgerEntry::getEndDate, ym.atDay(1));
        }
        wrapper.eq(StringUtils.isNotBlank(direction), LedgerEntry::getDirection, direction)
            .eq(deptId != null, LedgerEntry::getDeptId, deptId)
            .eq(subjectId != null, LedgerEntry::getSubjectId, subjectId)
            .orderByDesc(LedgerEntry::getStartDate)
            .orderByDesc(LedgerEntry::getId);

        List<LedgerEntry> entries = entryMapper.selectList(wrapper);
        List<LedgerSubject> subjects = subjectMapper.selectList(null);
        Map<Long, LedgerSubject> subjectMap = subjects.stream()
            .collect(Collectors.toMap(LedgerSubject::getId, s -> s, (a, b) -> a));
        Map<Long, String> deptNames = entryMapper.selectActiveDepts().stream()
            .collect(Collectors.toMap(DeptRowDTO::getDeptId, DeptRowDTO::getDeptName, (a, b) -> a));

        List<LedgerEntryVO> result = new ArrayList<>(entries.size());
        for (LedgerEntry e : entries) {
            LedgerEntryVO vo = new LedgerEntryVO();
            org.springframework.beans.BeanUtils.copyProperties(e, vo);
            LedgerSubject subject = subjectMap.get(e.getSubjectId());
            if (subject != null) {
                vo.setSubjectName(subject.getSubjectName());
                vo.setSubjectPath(buildSubjectPath(subject, subjectMap));
            }
            vo.setDeptName(deptNames.getOrDefault(e.getDeptId(), String.valueOf(e.getDeptId())));
            result.add(vo);
        }
        return result;
    }

    /**
     * 新增录入：同一科目 + 同一部门 + 相同生效/失效日期视为同一条业务，存在则更新金额与备注，不再重复新增。
     */
    public Long create(LedgerEntry entry, Long operatorId) {
        validate(entry);
        LedgerEntry existing = findSamePeriod(entry.getSubjectId(), entry.getDeptId(),
            entry.getStartDate(), entry.getEndDate(), null);
        if (existing != null) {
            existing.setAmount(entry.getAmount());
            existing.setRemark(entry.getRemark());
            existing.setUpdateTime(LocalDateTime.now());
            entryMapper.updateById(existing);
            return existing.getId();
        }
        entry.setCreateBy(operatorId);
        entry.setCreateTime(LocalDateTime.now());
        entry.setUpdateTime(LocalDateTime.now());
        entryMapper.insert(entry);
        return entry.getId();
    }

    /** 查找同科目 + 同部门 + 同生效/失效日期的记录（excludeId 用于编辑时排除自身） */
    private LedgerEntry findSamePeriod(Long subjectId, Long deptId, LocalDate startDate, LocalDate endDate, Long excludeId) {
        return entryMapper.selectOne(new LambdaQueryWrapper<LedgerEntry>()
            .eq(LedgerEntry::getSubjectId, subjectId)
            .eq(LedgerEntry::getDeptId, deptId)
            .eq(LedgerEntry::getStartDate, startDate)
            .eq(LedgerEntry::getEndDate, endDate)
            .ne(excludeId != null, LedgerEntry::getId, excludeId)
            .last("limit 1"));
    }

    public void update(LedgerEntry input) {
        if (input.getId() == null) {
            throw new ServiceException("缺少记录 ID");
        }
        LedgerEntry db = entryMapper.selectById(input.getId());
        if (db == null) {
            throw new ServiceException("录入记录不存在");
        }
        validate(input);
        if (findSamePeriod(input.getSubjectId(), input.getDeptId(), input.getStartDate(), input.getEndDate(),
            input.getId()) != null) {
            throw new ServiceException("已存在相同科目、相同部门的同一期间录入，请直接修改已有记录");
        }
        db.setSubjectId(input.getSubjectId());
        db.setDeptId(input.getDeptId());
        db.setDirection(input.getDirection());
        db.setAmount(input.getAmount());
        db.setStartDate(input.getStartDate());
        db.setEndDate(input.getEndDate());
        db.setRemark(input.getRemark());
        db.setUpdateTime(LocalDateTime.now());
        entryMapper.updateById(db);
    }

    public void delete(Long id) {
        if (entryMapper.deleteById(id) == 0) {
            throw new ServiceException("录入记录不存在或已删除");
        }
    }

    private void validate(LedgerEntry entry) {
        if (entry.getSubjectId() == null) {
            throw new ServiceException("请选择收支科目");
        }
        subjectService.assertManualLeaf(entry.getSubjectId());
        String direction = subjectService.getById(entry.getSubjectId()).getDirection();
        entry.setDirection(direction);

        if (entry.getDeptId() == null) {
            throw new ServiceException("请选择部门");
        }
        assertLeafDept(entry.getDeptId());

        if (entry.getAmount() == null || entry.getAmount().signum() == 0) {
            throw new ServiceException("金额不能为空或为 0");
        }
        entry.setAmount(entry.getAmount().setScale(2, java.math.RoundingMode.HALF_UP));
        if (entry.getStartDate() == null || entry.getEndDate() == null) {
            throw new ServiceException("请选择生效期间");
        }
        if (entry.getStartDate().isAfter(entry.getEndDate())) {
            throw new ServiceException("生效日期不能晚于失效日期");
        }
    }

    /** 部门必须是启用且无下级的叶子节点，避免报表无法归属 */
    private void assertLeafDept(Long deptId) {
        List<DeptRowDTO> depts = entryMapper.selectActiveDepts();
        Map<Long, DeptRowDTO> map = depts.stream()
            .collect(Collectors.toMap(DeptRowDTO::getDeptId, d -> d, (a, b) -> a));
        DeptRowDTO dept = map.get(deptId);
        if (dept == null) {
            throw new ServiceException("部门不存在或已停用");
        }
        Set<Long> parents = depts.stream()
            .map(DeptRowDTO::getParentId)
            .collect(Collectors.toSet());
        if (parents.contains(deptId)) {
            throw new ServiceException("只能在末级部门录入");
        }
    }

    private String buildSubjectPath(LedgerSubject subject, Map<Long, LedgerSubject> map) {
        List<String> names = new ArrayList<>();
        LedgerSubject cur = subject;
        int guard = 0;
        while (cur != null && guard++ < 10) {
            names.add(0, cur.getSubjectName());
            if (cur.getParentId() == null || cur.getParentId() == 0L) {
                break;
            }
            cur = map.get(cur.getParentId());
        }
        return String.join(" / ", names);
    }

    /**
     * 批量导入：先整体校验（科目路径 + 部门名称 + 金额 + 期间），全部通过才写入，避免产生半批脏数据。
     * <p>方向为空时按科目路径自动判定；科目路径支持「一级/二级/三级/四级」，分隔符两侧空格忽略。</p>
     */
    public LedgerImportResultVO importEntries(List<LedgerEntryExcelRow> rows, Long operatorId) {
        if (rows == null || rows.isEmpty()) {
            throw new ServiceException("导入文件没有可读取的数据，请使用系统提供的模板");
        }
        List<LedgerSubject> subjects = new ArrayList<>();
        subjects.addAll(subjectService.list(LedgerSubjectService.DIRECTION_INCOME));
        subjects.addAll(subjectService.list(LedgerSubjectService.DIRECTION_EXPENSE));
        Map<Long, LedgerSubject> subjectMap = subjects.stream()
            .collect(Collectors.toMap(LedgerSubject::getId, s -> s, (a, b) -> a));
        Map<String, LedgerSubject> pathMap = new HashMap<>();
        subjects.forEach(s -> pathMap.put(s.getDirection() + "|" + normalizePath(buildSubjectPath(s, subjectMap)), s));

        Map<String, List<DeptRowDTO>> deptByName = entryMapper.selectActiveDepts().stream()
            .collect(Collectors.groupingBy(d -> StringUtils.trim(d.getDeptName())));

        List<LedgerEntry> valid = new ArrayList<>();
        LedgerImportResultVO result = new LedgerImportResultVO();
        result.setTotal(rows.size());
        for (int i = 0; i < rows.size(); i++) {
            LedgerEntryExcelRow row = rows.get(i);
            int lineNo = i + 2;
            if (isBlankRow(row)) {
                continue;
            }
            try {
                LedgerEntry entry = convertRow(row, pathMap, deptByName);
                validate(entry);
                valid.add(entry);
            } catch (ServiceException e) {
                result.getErrors().add("第" + lineNo + "行：" + e.getMessage());
            } catch (Exception e) {
                result.getErrors().add("第" + lineNo + "行：" + e.getMessage());
            }
        }
        if (!result.getErrors().isEmpty()) {
            result.setFailCount(result.getErrors().size());
            result.setSuccessCount(0);
            return result;
        }
        for (LedgerEntry entry : valid) {
            // 同科目 + 同部门 + 同期间视为同一条业务：重复导入时更新金额与备注，不再重复新增
            create(entry, operatorId);
        }
        result.setSuccessCount(valid.size());
        return result;
    }

    private LedgerEntry convertRow(LedgerEntryExcelRow row, Map<String, LedgerSubject> pathMap,
                                   Map<String, List<DeptRowDTO>> deptByName) {
        String direction = parseDirection(row.getDirection());
        String path = normalizePath(StringUtils.trim(row.getSubjectPath()));
        if (StringUtils.isBlank(path)) {
            throw new ServiceException("科目路径不能为空");
        }
        LedgerSubject subject;
        if (StringUtils.isNotBlank(direction)) {
            subject = pathMap.get(direction + "|" + path);
        } else {
            List<LedgerSubject> matched = pathMap.entrySet().stream()
                .filter(e -> e.getKey().substring(e.getKey().indexOf('|') + 1).equals(path))
                .map(Map.Entry::getValue)
                .toList();
            subject = matched.size() == 1 ? matched.get(0) : null;
        }
        if (subject == null) {
            throw new ServiceException("科目路径不存在：" + path + "，请按「一级/二级/三级」完整填写");
        }

        String deptName = StringUtils.trim(row.getDeptName());
        List<DeptRowDTO> matchedDepts = deptByName.get(deptName);
        if (matchedDepts == null || matchedDepts.isEmpty()) {
            throw new ServiceException("部门不存在或已停用：" + deptName);
        }
        if (matchedDepts.size() > 1) {
            throw new ServiceException("部门名称重复，无法唯一识别：" + deptName);
        }

        LedgerEntry entry = new LedgerEntry();
        entry.setSubjectId(subject.getId());
        entry.setDeptId(matchedDepts.get(0).getDeptId());
        entry.setDirection(subject.getDirection());
        entry.setAmount(parseAmount(row.getAmount()));
        entry.setStartDate(parseDate(row.getStartDate(), "生效日期"));
        entry.setEndDate(parseDate(row.getEndDate(), "失效日期"));
        entry.setRemark(StringUtils.trim(row.getRemark()));
        return entry;
    }

    private boolean isBlankRow(LedgerEntryExcelRow row) {
        return StringUtils.isBlank(row.getDirection()) && StringUtils.isBlank(row.getSubjectPath())
            && StringUtils.isBlank(row.getDeptName()) && StringUtils.isBlank(row.getAmount())
            && StringUtils.isBlank(row.getStartDate()) && StringUtils.isBlank(row.getEndDate());
    }

    private String parseDirection(String text) {
        String value = StringUtils.trim(text);
        if (StringUtils.isBlank(value)) {
            return null;
        }
        if ("收入".equals(value) || "INCOME".equalsIgnoreCase(value)) {
            return LedgerSubjectService.DIRECTION_INCOME;
        }
        if ("支出".equals(value) || "EXPENSE".equalsIgnoreCase(value)) {
            return LedgerSubjectService.DIRECTION_EXPENSE;
        }
        throw new ServiceException("方向只能填写「收入」或「支出」：" + value);
    }

    private BigDecimal parseAmount(String text) {
        String value = StringUtils.trim(text);
        if (StringUtils.isBlank(value)) {
            throw new ServiceException("金额不能为空");
        }
        try {
            return new BigDecimal(value.replace(",", "").replace("￥", "").replace("¥", "").trim());
        } catch (NumberFormatException e) {
            throw new ServiceException("金额格式不正确：" + value);
        }
    }

    private LocalDate parseDate(String text, String label) {
        String value = StringUtils.trim(text);
        if (StringUtils.isBlank(value)) {
            throw new ServiceException(label + "不能为空");
        }
        String datePart = value.length() > 10 ? value.substring(0, 10) : value;
        for (String pattern : new String[]{"yyyy-MM-dd", "yyyy/M/d", "yyyy.M.d", "yyyyMMdd"}) {
            try {
                return LocalDate.parse(datePart, java.time.format.DateTimeFormatter.ofPattern(pattern));
            } catch (Exception ignored) {
                // 尝试下一种格式
            }
        }
        throw new ServiceException(label + "格式应为 YYYY-MM-DD：" + value);
    }

    private String normalizePath(String path) {
        return StringUtils.isBlank(path) ? "" : path.replaceAll("\\s+", "");
    }

    public static YearMonth parsePeriod(String period) {
        try {
            return YearMonth.parse(period);
        } catch (Exception e) {
            throw new ServiceException("归属月格式应为 YYYY-MM");
        }
    }

    /** 归属月快捷转换：2026-06 → 2026-06-01 ~ 2026-06-30 */
    public static LocalDate[] monthRange(String period) {
        YearMonth ym = parsePeriod(period);
        return new LocalDate[]{ym.atDay(1), ym.atEndOfMonth()};
    }
}
