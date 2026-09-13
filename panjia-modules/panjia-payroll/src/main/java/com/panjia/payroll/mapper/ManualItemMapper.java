package com.panjia.payroll.mapper;

import com.panjia.payroll.domain.ManualItem;
import org.apache.ibatis.annotations.Mapper;
import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;

@Mapper
public interface ManualItemMapper extends BaseMapperPlus<ManualItem, ManualItem> {
}
