package com.panjia.importdomain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.panjia.importdomain.domain.raw.RawEmployee;
import org.apache.ibatis.annotations.Mapper;

/**
 * 员工 RawData Mapper。insert-only。
 */
@Mapper
public interface RawEmployeeMapper extends BaseMapper<RawEmployee> {
}
