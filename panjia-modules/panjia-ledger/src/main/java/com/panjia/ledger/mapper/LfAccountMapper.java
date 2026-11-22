package com.panjia.ledger.mapper;

import com.panjia.ledger.domain.LfAccount;
import org.apache.ibatis.annotations.Mapper;
import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;

@Mapper
public interface LfAccountMapper extends BaseMapperPlus<LfAccount, LfAccount> {
}
