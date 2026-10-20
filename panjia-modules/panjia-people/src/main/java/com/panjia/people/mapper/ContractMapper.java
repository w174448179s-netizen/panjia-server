package com.panjia.people.mapper;

import com.panjia.people.domain.Contract;
import org.apache.ibatis.annotations.Mapper;
import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;

/**
 * 员工合同 Mapper。
 */
@Mapper
public interface ContractMapper extends BaseMapperPlus<Contract, Contract> {
}
