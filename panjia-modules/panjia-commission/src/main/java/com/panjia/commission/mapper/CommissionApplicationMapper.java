package com.panjia.commission.mapper;

import com.panjia.commission.domain.CommissionApplication;
import org.apache.ibatis.annotations.Mapper;
import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;

/**
 * 结佣申请单 Mapper。
 */
@Mapper
public interface CommissionApplicationMapper extends BaseMapperPlus<CommissionApplication, CommissionApplication> {
}
