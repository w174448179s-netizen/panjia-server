package com.panjia.received.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.panjia.received.domain.ReceivedContract;
import org.apache.ibatis.annotations.Mapper;

/**
 * 实收合同 Mapper。
 * <p>
 * 单表等值查询一律用 {@code LambdaQueryWrapper + BaseMapper.selectOne/selectCount}，
 * 不声明自定义方法；需要 JOIN / 聚合 / 动态 SQL 时再按仓库注解 SQL 规范加 @Select。
 */
@Mapper
public interface ReceivedContractMapper extends BaseMapper<ReceivedContract> {
}
