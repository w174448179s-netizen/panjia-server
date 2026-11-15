package com.panjia.people.mapper;

import com.panjia.people.domain.ImUserMapping;
import org.apache.ibatis.annotations.Mapper;
import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;

/**
 * IM 账号映射 Mapper。
 */
@Mapper
public interface ImUserMappingMapper extends BaseMapperPlus<ImUserMapping, ImUserMapping> {
}
