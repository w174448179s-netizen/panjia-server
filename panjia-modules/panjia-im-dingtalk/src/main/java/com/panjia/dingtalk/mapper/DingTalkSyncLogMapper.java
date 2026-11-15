package com.panjia.dingtalk.mapper;

import com.panjia.dingtalk.domain.DingTalkSyncLog;
import org.apache.ibatis.annotations.Mapper;
import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;

/**
 * 钉钉同步日志 Mapper。
 */
@Mapper
public interface DingTalkSyncLogMapper extends BaseMapperPlus<DingTalkSyncLog, DingTalkSyncLog> {
}
