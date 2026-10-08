package com.panjia.payroll.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.panjia.payroll.domain.DeptMonthlyConfig;
import com.panjia.payroll.mapper.DeptMonthlyConfigMapper;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.exception.ServiceException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 门店月度算薪配置服务（新签与结佣差额等，按门店 × 月份维护）。
 */
@Service
@RequiredArgsConstructor
public class DeptMonthlyConfigService {

    private final DeptMonthlyConfigMapper mapper;

    /** 算薪取数：deptId → 当月新签与结佣差额（未配置门店不在 Map 中，调用方按 0 处理）。 */
    public Map<Long, BigDecimal> loadDiffForPeriod(String period) {
        Map<Long, BigDecimal> result = new HashMap<>();
        for (DeptMonthlyConfig c : listByPeriod(period)) {
            if (c.getDeptId() == null || c.getDiffAmount() == null) {
                continue;
            }
            result.put(c.getDeptId(), c.getDiffAmount());
        }
        return result;
    }

    public List<DeptMonthlyConfig> listByPeriod(String period) {
        return mapper.selectList(new LambdaQueryWrapper<DeptMonthlyConfig>()
            .eq(DeptMonthlyConfig::getPeriod, period)
            .orderByAsc(DeptMonthlyConfig::getDeptId));
    }

    /**
     * 批量保存某月门店配置（按 dept_id+period upsert；差额为 0/空且无备注的行删除）。
     */
    @Transactional(rollbackFor = Exception.class)
    public void saveRows(String period, List<DeptMonthlyConfig> rows) {
        if (period == null || period.isBlank()) {
            throw new ServiceException("归属月不能为空");
        }
        if (rows == null) {
            return;
        }
        for (DeptMonthlyConfig row : rows) {
            if (row.getDeptId() == null) {
                continue;
            }
            BigDecimal diff = row.getDiffAmount() == null ? BigDecimal.ZERO : row.getDiffAmount();
            DeptMonthlyConfig existing = mapper.selectOne(new LambdaQueryWrapper<DeptMonthlyConfig>()
                .eq(DeptMonthlyConfig::getPeriod, period)
                .eq(DeptMonthlyConfig::getDeptId, row.getDeptId())
                .last("LIMIT 1"));
            boolean blank = diff.signum() == 0 && (row.getRemark() == null || row.getRemark().isBlank());
            if (existing == null) {
                if (blank) {
                    continue;
                }
                DeptMonthlyConfig entity = new DeptMonthlyConfig();
                entity.setPeriod(period);
                entity.setDeptId(row.getDeptId());
                entity.setDiffAmount(diff);
                entity.setRemark(row.getRemark());
                mapper.insert(entity);
            } else if (blank) {
                mapper.deleteById(existing.getId());
            } else {
                existing.setDiffAmount(diff);
                existing.setRemark(row.getRemark());
                mapper.updateById(existing);
            }
        }
    }
}
