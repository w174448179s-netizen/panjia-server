package com.panjia.people.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;
import com.panjia.people.domain.Employee;

/**
 * 员工主数据 Mapper。
 */
@Mapper
public interface EmployeeMapper extends BaseMapperPlus<Employee, Employee> {
}
