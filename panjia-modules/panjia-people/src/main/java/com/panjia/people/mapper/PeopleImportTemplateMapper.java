package com.panjia.people.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;
import com.panjia.people.domain.PeopleImportTemplate;

/**
 * 员工导入模板 Mapper。
 */
@Mapper
public interface PeopleImportTemplateMapper extends BaseMapperPlus<PeopleImportTemplate, PeopleImportTemplate> {
}
