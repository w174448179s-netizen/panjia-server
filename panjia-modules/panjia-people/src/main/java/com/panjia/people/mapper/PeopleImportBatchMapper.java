package com.panjia.people.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;
import com.panjia.people.domain.PeopleImportBatch;

/**
 * 员工导入批次 Mapper。
 */
@Mapper
public interface PeopleImportBatchMapper extends BaseMapperPlus<PeopleImportBatch, PeopleImportBatch> {
}
