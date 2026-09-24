package com.panjia.importdomain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.panjia.importdomain.domain.raw.RawPayroll;
import org.apache.ibatis.annotations.Mapper;

/**
 * 历史工资族 RawData Mapper。insert-only：仅使用 insert / select 系列方法。
 */
@Mapper
public interface RawPayrollMapper extends BaseMapper<RawPayroll> {
}
