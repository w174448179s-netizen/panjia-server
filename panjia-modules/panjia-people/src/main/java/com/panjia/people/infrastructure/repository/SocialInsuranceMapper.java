package com.panjia.people.infrastructure.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.panjia.people.domain.SocialInsuranceProfile;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 社保档案 Mapper（对应 pj_people_social_insurance）。
 */
@Mapper
public interface SocialInsuranceMapper extends BaseMapper<SocialInsuranceProfile> {

    /**
     * 按员工 ID 查询当前社保档案（effective_to 为空）。
     *
     * @param employeeId 员工 ID
     * @return 社保档案，无则 null
     */
    @Select("SELECT * FROM pj_people_social_insurance WHERE employee_id = #{employeeId} AND effective_to IS NULL LIMIT 1")
    SocialInsuranceProfile selectByEmployeeId(@Param("employeeId") Long employeeId);
}
