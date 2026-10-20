package com.panjia.people.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;
import com.panjia.people.domain.Employee;

/**
 * 员工主数据 Mapper。
 */
@Mapper
public interface EmployeeMapper extends BaseMapperPlus<Employee, Employee> {

    /**
     * 取指定员工的工资查询密码 hash（单列查询，避免 selectById 泄露密码字段到日志等）。
     *
     * @param employeeId 员工 ID
     * @return BCrypt hash；null=未设置密码
     */
    @Select("SELECT salary_query_password FROM pj_people_employee WHERE employee_id = #{employeeId}")
    String selectSalaryQueryPasswordHash(@Param("employeeId") Long employeeId);
}
