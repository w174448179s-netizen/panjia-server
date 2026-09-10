package com.panjia.people.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;
import com.panjia.people.domain.PeopleImportIssue;

/**
 * 员工导入问题清单 Mapper。
 */
@Mapper
public interface PeopleImportIssueMapper extends BaseMapperPlus<PeopleImportIssue, PeopleImportIssue> {
}
