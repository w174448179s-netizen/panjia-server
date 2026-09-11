package com.panjia.performance.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;
import com.panjia.performance.domain.PerformanceConsumeLog;

/**
 * 消费日志 Mapper。
 */
@Mapper
public interface PerformanceConsumeLogMapper extends BaseMapperPlus<PerformanceConsumeLog, PerformanceConsumeLog> {
}
