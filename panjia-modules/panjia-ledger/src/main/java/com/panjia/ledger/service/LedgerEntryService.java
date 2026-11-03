package com.panjia.ledger.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.panjia.ledger.domain.LedgerEntry;
import com.panjia.ledger.domain.LedgerSubject;
import com.panjia.ledger.dto.DeptRowDTO;
import com.panjia.ledger.dto.LedgerEntryVO;
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

    public Long create(LedgerEntry entry, Long operatorId) {
        validate(entry);
        entry.setCreateBy(operatorId);
        entry.setCreateTime(LocalDateTime.now());
        entry.setUpdateTime(LocalDateTime.now());
        entryMapper.insert(entry);
        return entry.getId();
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
