package com.panjia.performance.mapper;

import com.baomidou.mybatisplus.core.toolkit.CollectionUtils;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;
import com.panjia.performance.domain.PerformanceAdjust;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 业绩调整单 Mapper。
 */
@Mapper
public interface PerformanceAdjustMapper extends BaseMapperPlus<PerformanceAdjust, PerformanceAdjust> {

    /**
     * 批量查询员工姓名（按员工 ID）。
     *
     * @param ids 员工 ID 集合
     * @return 每行含 employee_id / employee_name；ids 为空时返回空列表
     */
    @Select("<script>"
        + "SELECT employee_id AS \"employeeId\", employee_name AS \"employeeName\" "
        + "FROM pj_people_employee "
        + "WHERE employee_id IN "
        + "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach>"
        + "</script>")
    List<Map<String, Object>> selectEmployeeNamesByIds(@Param("ids") List<Long> ids);

    /**
     * 批量查询部门名称（按部门 ID）。
     *
     * @param ids 部门 ID 集合
     * @return 每行含 dept_id / dept_name；ids 为空时返回空列表
     */
    @Select("<script>"
        + "SELECT dept_id AS \"deptId\", dept_name AS \"deptName\" "
        + "FROM sys_dept "
        + "WHERE dept_id IN "
        + "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach>"
        + "</script>")
    List<Map<String, Object>> selectDeptNamesByIds(@Param("ids") List<Long> ids);

    /** 空集合安全：调用方直接传，无需判空 */
    default List<Map<String, Object>> employeeNames(List<Long> ids) {
        if (CollectionUtils.isEmpty(ids)) {
            return Collections.emptyList();
        }
        return selectEmployeeNamesByIds(ids);
    }

    default List<Map<String, Object>> deptNames(List<Long> ids) {
        if (CollectionUtils.isEmpty(ids)) {
            return Collections.emptyList();
        }
        return selectDeptNamesByIds(ids);
    }
}
