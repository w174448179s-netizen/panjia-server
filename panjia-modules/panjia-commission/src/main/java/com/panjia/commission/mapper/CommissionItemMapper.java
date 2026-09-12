package com.panjia.commission.mapper;

import com.panjia.commission.domain.CommissionItem;
import org.apache.ibatis.annotations.Mapper;
import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;

/**
 * 结佣明细 Mapper。
 * <p>
 * CI C13：不得含 updateAmount 类方法 —— 已审批明细金额不可变，变更只能走调整单（新行）。
 */
@Mapper
public interface CommissionItemMapper extends BaseMapperPlus<CommissionItem, CommissionItem> {
}
