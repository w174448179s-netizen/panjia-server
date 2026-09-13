package com.panjia.payroll.mapper;

import com.panjia.payroll.domain.PayrollEmployeeSnapshot;
import org.apache.ibatis.annotations.Mapper;
import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;

@Mapper
public interface PayrollEmployeeSnapshotMapper extends BaseMapperPlus<PayrollEmployeeSnapshot, PayrollEmployeeSnapshot> {
}
