package com.panjia.people.infrastructure.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.panjia.people.domain.RoleMapping;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 业务角色→系统角色映射 Mapper（对应 pj_people_role_mapping）。
 */
@Mapper
public interface RoleMappingMapper extends BaseMapper<RoleMapping> {

    /**
     * 按业务角色查询所有启用的 sys_role_id 列表。
     *
     * @param employeeRole 业务角色 code（如 STORE_MANAGER）
     * @return sys_role_id 列表
     */
    @Select("SELECT sys_role_id FROM pj_people_role_mapping " +
        "WHERE employee_role = #{employeeRole} AND is_active = TRUE")
    List<Long> selectSysRoleIdsByEmployeeRole(@Param("employeeRole") String employeeRole);
}
