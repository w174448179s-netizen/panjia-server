package com.panjia.performance.mapper;

import com.panjia.performance.domain.PerformanceViewLog;
import org.apache.ibatis.annotations.Mapper;
import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;

/**
 * 业绩查看留痕 Mapper。
 * <p>仅提供插入与查询，不提供删除（留痕只读）。
 */
@Mapper
public interface PerformanceViewLogMapper extends BaseMapperPlus<PerformanceViewLog, PerformanceViewLog> {
}
