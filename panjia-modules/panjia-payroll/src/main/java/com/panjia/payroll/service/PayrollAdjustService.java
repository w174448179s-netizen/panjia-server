package com.panjia.payroll.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.panjia.payroll.domain.PayrollAdjust;
import com.panjia.payroll.mapper.PayrollAdjustMapper;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.core.domain.PageResult;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

/** 调整/补发单服务：直接登记（APPROVED），算薪执行后置 EXECUTED；该单不走工作流（表无流程实例列）。 */
@Service
@RequiredArgsConstructor
public class PayrollAdjustService {

    private final PayrollAdjustMapper mapper;

    /** 分页查询（目标期间/类型/状态/员工筛选）。 */
    public PageResult<PayrollAdjust> pageList(String period, String adjustType, String status,
                                              Long employeeId, PageQuery pageQuery) {
        LambdaQueryWrapper<PayrollAdjust> wrapper = new LambdaQueryWrapper<PayrollAdjust>()
            .eq(StringUtils.isNotBlank(period), PayrollAdjust::getTargetPeriod, period)
            .eq(StringUtils.isNotBlank(adjustType), PayrollAdjust::getAdjustType, adjustType)
            .eq(StringUtils.isNotBlank(status), PayrollAdjust::getStatus, status)
            .eq(employeeId != null, PayrollAdjust::getEmployeeId, employeeId)
            .orderByDesc(PayrollAdjust::getCreateTime);
        var page = mapper.selectPage(pageQuery.build(), wrapper);
        return PageResult.build(page.getRecords(), page.getTotal());
    }

    /** 登记调整/补发单（V1 直接 APPROVED 生效，后续如需审批可接工作流）。 */
    public Long create(PayrollAdjust item, Long operatorId) {
        if (StringUtils.isBlank(item.getTargetPeriod())) {
            throw new ServiceException("请选择目标期间");
        }
        if (item.getEmployeeId() == null) {
            throw new ServiceException("请选择员工");
        }
        if (item.getAdjustType() == null) {
            throw new ServiceException("请选择调整类型");
        }
        if (item.getAmount() == null || item.getAmount().compareTo(BigDecimal.ZERO) == 0) {
            throw new ServiceException("调整金额不能为 0");
        }
        if (StringUtils.isBlank(item.getReason())) {
            throw new ServiceException("请填写调整原因");
        }
        item.setId(null);
        item.setStatus("APPROVED");
        item.setOperatorId(operatorId);
        mapper.insert(item);
        return item.getId();
    }

    public PayrollAdjust getById(Long id) {
        return mapper.selectById(id);
    }
}
