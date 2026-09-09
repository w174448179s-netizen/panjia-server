package com.panjia.people.mapper;

import com.panjia.people.domain.ChangeLog;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;

import java.util.List;

/**
 * 员工变更审计日志 Mapper。
 */
@Mapper
public interface ChangeLogMapper extends BaseMapperPlus<ChangeLog, ChangeLog> {

    /**
     * 按员工查变更时间线（生效日倒序、同生效日按创建时间倒序）。
     *
     * @param employeeId 员工 ID
     * @return 变更日志列表
     */
    @Select("SELECT * FROM pj_people_change_log WHERE employee_id = #{employeeId} " +
        "ORDER BY effective_date DESC, create_time DESC")
    List<ChangeLog> selectByEmployee(@Param("employeeId") Long employeeId);
}
