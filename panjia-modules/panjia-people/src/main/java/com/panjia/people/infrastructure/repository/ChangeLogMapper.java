package com.panjia.people.infrastructure.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.panjia.people.domain.ChangeLog;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;

/**
 * 员工变更日志 Mapper（对应 pj_people_change_log）。
 */
@Mapper
public interface ChangeLogMapper extends BaseMapper<ChangeLog> {

    /**
     * 查询员工在指定时点之前最近一次角色变更的新值（即该时点的角色）。
     * <p>
     * 用于快照取时点角色：若返回 null 则表示该时点前无角色变更，角色自建档以来未变。
     *
     * @param employeeId 员工 ID
     * @param pointInTime 快照时点
     * @return 角色枚举名（如 AGENT），无记录返回 null
     */
    @Select("SELECT new_value FROM pj_people_change_log " +
        "WHERE employee_id = #{employeeId} " +
        "AND change_type = 'ROLE_CHANGE' " +
        "AND operated_at <= #{pointInTime} " +
        "ORDER BY operated_at DESC LIMIT 1")
    String selectRoleAtPoint(@Param("employeeId") Long employeeId,
                             @Param("pointInTime") LocalDateTime pointInTime);
}
