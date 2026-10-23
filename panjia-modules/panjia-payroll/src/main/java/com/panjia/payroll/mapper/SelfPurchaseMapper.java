package com.panjia.payroll.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.panjia.payroll.domain.SelfPurchase;
import org.apache.ibatis.annotations.Mapper;

/**
 * 经纪人自购单提成 Mapper。
 */
@Mapper
public interface SelfPurchaseMapper extends BaseMapper<SelfPurchase> {
}
