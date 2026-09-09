package com.panjia.people.infrastructure.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.panjia.people.domain.Employee;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;
import java.util.List;

/**
 * 员工 Mapper（对应 pj_people_employee）。
 * <p>
 * 关联装配（levelHistory / socialInsurance）由应用层调用对应 Mapper 完成。
 */
@Mapper
public interface EmployeeMapper extends BaseMapper<Employee> {

    /**
     * 判断工号是否已存在。
     *
     * @param employeeCode 工号
     * @return true 表示已存在
     */
    @Select("SELECT COUNT(1) > 0 FROM pj_people_employee WHERE LOWER(employee_code) = LOWER(#{employeeCode})")
    boolean existsByEmployeeCode(@Param("employeeCode") String employeeCode);

    /**
     * 按工号查询员工（大小写不敏感，导入匹配用）。
     *
     * @param employeeCode 工号
     * @return 员工，不存在返回 null
     */
    @Select("SELECT * FROM pj_people_employee WHERE LOWER(employee_code) = LOWER(#{employeeCode}) LIMIT 1")
    Employee selectByEmployeeCode(@Param("employeeCode") String employeeCode);

    /**
     * 查询指定部门在某时点所有在职员工的 ID（用于门店维度算薪快照）。
     * <p>
     * 在职判定：status='ACTIVE' 且 hire_date &lt;= pointInTime
     * 且（resign_date 为空或 resign_date &gt; pointInTime）。
     *
     * @param deptId      部门 ID
     * @param pointInTime 时点
     * @return 员工 ID 列表
     */
    @Select("SELECT id FROM pj_people_employee " +
        "WHERE dept_id = #{deptId} " +
        "AND status = 'ACTIVE' " +
        "AND hire_date <= #{pointInTime} " +
        "AND (resign_date IS NULL OR resign_date > #{pointInTime}) " +
        "ORDER BY id")
    List<Long> selectActiveIdsByDept(@Param("deptId") Long deptId, @Param("pointInTime") LocalDate pointInTime);
}
