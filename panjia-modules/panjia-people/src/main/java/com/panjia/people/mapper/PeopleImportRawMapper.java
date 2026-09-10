package com.panjia.people.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;
import com.panjia.people.domain.PeopleImportRaw;

/**
 * 员工导入原始行 Mapper（insert-only）。
 */
@Mapper
public interface PeopleImportRawMapper extends BaseMapperPlus<PeopleImportRaw, PeopleImportRaw> {
}
