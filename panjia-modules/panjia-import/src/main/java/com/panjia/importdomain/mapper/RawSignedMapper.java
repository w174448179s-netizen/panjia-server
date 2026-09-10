package com.panjia.importdomain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.panjia.importdomain.domain.raw.RawSigned;
import org.apache.ibatis.annotations.Mapper;

/**
 * 贝壳结佣 RawData Mapper。insert-only：仅使用 insert / select 系列方法。
 */
@Mapper
public interface RawSignedMapper extends BaseMapper<RawSigned> {
}
