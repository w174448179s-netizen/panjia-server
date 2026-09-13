package com.panjia.payroll.mapper;

import com.panjia.payroll.domain.PayrollBatch;
import org.apache.ibatis.annotations.Mapper;
import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;

@Mapper
public interface PayrollBatchMapper extends BaseMapperPlus<PayrollBatch, PayrollBatch> {
}
