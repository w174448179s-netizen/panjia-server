package com.panjia.people.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;
import com.panjia.people.domain.AttendanceRecord;

/**
 * 日考勤明细 Mapper。
 */
@Mapper
public interface AttendanceRecordMapper extends BaseMapperPlus<AttendanceRecord, AttendanceRecord> {
}
