package com.panjia.ledger.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.panjia.ledger.domain.LedgerEntry;
import com.panjia.ledger.domain.LedgerSubject;
import com.panjia.ledger.mapper.LedgerEntryMapper;
import com.panjia.ledger.mapper.LedgerSubjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.StringUtils;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 收支科目配置服务。
 * <p>规则：收入/支出各自独立成树；最多 4 级；只有叶子节点可被录入；
 * 自动取数科目（实收/工资）必须是叶子，且不允许手工录入。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LedgerSubjectService {

    public static final String DIRECTION_INCOME = "INCOME";
    public static final String DIRECTION_EXPENSE = "EXPENSE";
    public static final String DATA_SOURCE_MANUAL = "MANUAL";
    public static final String DATA_SOURCE_RECEIVED = "RECEIVED_DETAIL";
    public static final String DATA_SOURCE_PAYROLL = "PAYROLL_GROSS";
    public static final int MAX_LEVEL = 4;

    private final LedgerSubjectMapper subjectMapper;
    private final LedgerEntryMapper entryMapper;

    /** 科目列表（扁平结构，前端按 parentId 组树） */
    public List<LedgerSubject> list(String direction) {
        List<LedgerSubject> list = subjectMapper.selectList(
            new LambdaQueryWrapper<LedgerSubject>()
                .eq(StringUtils.isNotBlank(direction), LedgerSubject::getDirection, direction)
                .orderByAsc(LedgerSubject::getParentId)
                .orderByAsc(LedgerSubject::getSortOrder)
                .orderByAsc(LedgerSubject::getId));
        list.forEach(s -> {
            long children = list.stream().filter(c -> c.getParentId() != null && c.getParentId().equals(s.getId())).count();
            s.setChildrenCount((int) children);
            s.setLeaf(children == 0);
        });
        return list;
    }

    /** 某方向启用的科目（报表用） */
    public List<LedgerSubject> listEnabled(String direction) {
        return subjectMapper.selectList(
            new LambdaQueryWrapper<LedgerSubject>()
                .eq(LedgerSubject::getDirection, direction)
                .eq(LedgerSubject::getStatus, "0")
                .orderByAsc(LedgerSubject::getSortOrder)
                .orderByAsc(LedgerSubject::getId));
    }

    public LedgerSubject getById(Long id) {
        LedgerSubject subject = subjectMapper.selectById(id);
        if (subject == null) {
            throw new ServiceException("科目不存在");
        }
        return subject;
    }

    public Long create(LedgerSubject subject) {
        validateDirection(subject.getDirection());
        if (StringUtils.isBlank(subject.getSubjectName())) {
            throw new ServiceException("科目名称不能为空");
        }
        subject.setDataSource(normalizeDataSource(subject.getDataSource()));

        Long parentId = subject.getParentId() == null ? 0L : subject.getParentId();
        int level = 1;
        if (parentId != 0L) {
            LedgerSubject parent = getById(parentId);
            if (!parent.getDirection().equals(subject.getDirection())) {
                throw new ServiceException("上级科目与当前方向不一致");
            }
            if (!DATA_SOURCE_MANUAL.equals(parent.getDataSource())) {
                throw new ServiceException("自动取数科目下不能新增子科目");
            }
            level = (parent.getLevel() == null ? 1 : parent.getLevel()) + 1;
        }
        if (level > MAX_LEVEL) {
            throw new ServiceException("科目层级最多支持 " + MAX_LEVEL + " 级");
        }
        subject.setParentId(parentId);
        subject.setLevel(level);
        subject.setSortOrder(subject.getSortOrder() == null ? 0 : subject.getSortOrder());
        subject.setStatus(StringUtils.isBlank(subject.getStatus()) ? "0" : subject.getStatus());
        subject.setCreateTime(LocalDateTime.now());
        subject.setUpdateTime(LocalDateTime.now());
        subjectMapper.insert(subject);
        return subject.getId();
    }

    public void update(LedgerSubject input) {
        LedgerSubject db = getById(input.getId());
        if (StringUtils.isBlank(input.getSubjectName())) {
            throw new ServiceException("科目名称不能为空");
        }
        String dataSource = normalizeDataSource(input.getDataSource());
        if (input.getDataSource() != null && !DATA_SOURCE_MANUAL.equals(dataSource)) {
            if (hasChildren(db.getId(), db.getDirection())) {
                throw new ServiceException("存在子科目，不能改为自动取数科目");
            }
            long entryCount = entryMapper.selectCount(
                new LambdaQueryWrapper<LedgerEntry>().eq(LedgerEntry::getSubjectId, db.getId()));
            if (entryCount > 0) {
                throw new ServiceException("该科目已有录入记录，不能改为自动取数科目");
            }
        }
        db.setSubjectName(input.getSubjectName());
        db.setSubjectCode(input.getSubjectCode());
        db.setSortOrder(input.getSortOrder() == null ? db.getSortOrder() : input.getSortOrder());
        db.setDataSource(input.getDataSource() == null ? db.getDataSource() : dataSource);
        db.setStatus(StringUtils.isBlank(input.getStatus()) ? db.getStatus() : input.getStatus());
        db.setRemark(input.getRemark());
        db.setUpdateTime(LocalDateTime.now());
        subjectMapper.updateById(db);
    }

    public void delete(Long id) {
        LedgerSubject db = getById(id);
        if (hasChildren(id, db.getDirection())) {
            throw new ServiceException("请先删除下级科目");
        }
        long entryCount = entryMapper.selectCount(
            new LambdaQueryWrapper<LedgerEntry>().eq(LedgerEntry::getSubjectId, id));
        if (entryCount > 0) {
            throw new ServiceException("该科目已有录入记录，不能删除");
        }
        subjectMapper.deleteById(id);
    }

    /** 校验科目可用于手工录入：启用、叶子、手工数据源 */
    public void assertManualLeaf(Long subjectId) {
        LedgerSubject subject = getById(subjectId);
        if (!"0".equals(subject.getStatus())) {
            throw new ServiceException("科目已停用，不能录入");
        }
        if (hasChildren(subjectId, subject.getDirection())) {
            throw new ServiceException("只能在末级科目录入");
        }
        if (!DATA_SOURCE_MANUAL.equals(subject.getDataSource())) {
            throw new ServiceException("该科目由系统自动取数，不允许手工录入");
        }
    }

    private boolean hasChildren(Long id, String direction) {
        return subjectMapper.selectCount(
            new LambdaQueryWrapper<LedgerSubject>()
                .eq(LedgerSubject::getDirection, direction)
                .eq(LedgerSubject::getParentId, id)) > 0;
    }

    private void validateDirection(String direction) {
        if (!DIRECTION_INCOME.equals(direction) && !DIRECTION_EXPENSE.equals(direction)) {
            throw new ServiceException("科目方向必须为收入或支出");
        }
    }

    private String normalizeDataSource(String dataSource) {
        if (StringUtils.isBlank(dataSource)) {
            return DATA_SOURCE_MANUAL;
        }
        if (!DATA_SOURCE_MANUAL.equals(dataSource)
            && !DATA_SOURCE_RECEIVED.equals(dataSource)
            && !DATA_SOURCE_PAYROLL.equals(dataSource)) {
            throw new ServiceException("不支持的数据来源：" + dataSource);
        }
        return dataSource;
    }
}
