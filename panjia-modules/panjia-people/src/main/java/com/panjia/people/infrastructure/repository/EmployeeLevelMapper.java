package com.panjia.people.infrastructure.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.panjia.people.domain.EmployeeLevel;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

/**
 * 职级 Mapper（对应 pj_people_level）。
 */
@Mapper
public interface EmployeeLevelMapper extends BaseMapper<EmployeeLevel> {

    /**
     * 查询员工职级历史（按生效日期倒序）。
     *
     * @param employeeId 员工 ID
     * @return 职级记录列表
     */
    @Select("SELECT * FROM pj_people_level WHERE employee_id = #{employeeId} ORDER BY effective_from DESC")
    List<EmployeeLevel> selectByEmployeeIdOrderByEffectiveFromDesc(@Param("employeeId") Long employeeId);

    /**
     * 批量查询多员工的职级历史（按生效日期倒序）。
     *
     * @param employeeIds 员工 ID 集合
     * @return 职级记录列表
     */
    @Select("<script>SELECT * FROM pj_people_level WHERE employee_id IN " +
        "<foreach collection='employeeIds' item='id' open='(' separator=',' close=')'>" +
        "#{id}</foreach> ORDER BY effective_from DESC</script>")
    List<EmployeeLevel> selectByEmployeeIds(@Param("employeeIds") Collection<Long> employeeIds);

    /**
     * 查询员工在指定时点的唯一生效职级记录（按生效日倒序取第一条覆盖时点的记录）。
     *
     * @param employeeId  员工 ID
     * @param pointInTime 时点
     * @return 生效职级，无则 null
     */
    @Select("SELECT * FROM pj_people_level " +
        "WHERE employee_id = #{employeeId} " +
        "AND effective_from <= #{pointInTime} " +
        "AND (effective_to IS NULL OR effective_to >= #{pointInTime}) " +
        "ORDER BY effective_from DESC LIMIT 1")
    EmployeeLevel selectEffectiveAt(@Param("employeeId") Long employeeId, @Param("pointInTime") LocalDate pointInTime);
}
