package com.panjia.commission.mapper;

import com.panjia.commission.domain.CommissionAdjust;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;

/**
 * 结佣调整单 Mapper。
 */
@Mapper
public interface CommissionAdjustMapper extends BaseMapperPlus<CommissionAdjust, CommissionAdjust> {

    /**
     * 查部门名（TRANSFER 调整单详情展示目标部门用）。
     *
     * @param deptId 部门 ID
     * @return 部门名；不存在返回 null
     */
    @Select("SELECT dept_name FROM sys_dept WHERE dept_id = #{deptId}")
    String selectDeptName(@Param("deptId") Long deptId);
}
