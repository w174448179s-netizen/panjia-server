package com.panjia.payroll.service;

import com.panjia.payroll.domain.ManualItem;
import com.panjia.payroll.domain.ManualItemType;
import com.panjia.payroll.mapper.ManualItemMapper;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.exception.ServiceException;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** 手工录入项服务（奖金/其他收入/其他支出） */
@Service
@RequiredArgsConstructor
public class ManualItemService {

    private final ManualItemMapper mapper;

    public Long create(ManualItem item, Long operatorId) {
        if (item.getItemType() == null) {
            throw new ServiceException("请选择录入类型");
        }
        item.setStatus("APPROVED"); // V1 直接生效，后续可走审批
        item.setApplyBy(operatorId);
        item.setApproveBy(operatorId);
        mapper.insert(item);
        return item.getId();
    }

    public List<ManualItem> listByPeriod(String period) {
        return mapper.selectList(
            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ManualItem>()
                .eq(ManualItem::getPeriod, period)
                .orderByDesc(ManualItem::getCreateTime));
    }

    public void delete(Long id, Long operatorId) {
        mapper.deleteById(id);
    }

    /** 取某期间已审批的手工项，按员工分组（算薪用） */
    public Map<Long, List<ManualItem>> loadApprovedForPeriod(String period) {
        List<ManualItem> items = mapper.selectList(
            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ManualItem>()
                .eq(ManualItem::getPeriod, period)
                .eq(ManualItem::getStatus, "APPROVED"));
        return items.stream().collect(Collectors.groupingBy(ManualItem::getEmployeeId));
    }
}
