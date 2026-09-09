package com.panjia.people.infrastructure.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.panjia.people.domain.MentorRelation;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 师徒关系 Mapper（对应 pj_people_mentor_relation）。
 */
@Mapper
public interface MentorRelationMapper extends BaseMapper<MentorRelation> {

    /**
     * 判断徒弟是否已有有效师傅。
     *
     * @param apprenticeId 徒弟员工 ID
     * @return true 表示已存在有效关系
     */
    @Select("SELECT COUNT(1) > 0 FROM pj_people_mentor_relation WHERE apprentice_id = #{apprenticeId} AND is_active = TRUE")
    boolean existsActiveByApprentice(@Param("apprenticeId") Long apprenticeId);

    /**
     * 统计师傅当前有效徒弟数（上限 5 人）。
     *
     * @param mentorId 师傅员工 ID
     * @return 有效徒弟数
     */
    @Select("SELECT COUNT(1) FROM pj_people_mentor_relation WHERE mentor_id = #{mentorId} AND is_active = TRUE")
    long countActiveByMentor(@Param("mentorId") Long mentorId);

    /**
     * 统计师傅的合格徒弟数（行业经验 &gt;= 2 年且在有效期内），用于招聘奖励 +N%。
     *
     * @param mentorId 师傅员工 ID
     * @return 合格徒弟数
     */
    @Select("SELECT COUNT(1) FROM pj_people_mentor_relation " +
        "WHERE mentor_id = #{mentorId} AND is_active = TRUE AND apprentice_industry_years >= 2.0")
    long countQualifiedByMentor(@Param("mentorId") Long mentorId);

    /**
     * 查询徒弟当前所有有效师徒关系（徒弟离职时批量失效）。
     *
     * @param apprenticeId 徒弟员工 ID
     * @return 有效关系列表
     */
    @Select("SELECT * FROM pj_people_mentor_relation WHERE apprentice_id = #{apprenticeId} AND is_active = TRUE")
    List<MentorRelation> selectActiveByApprentice(@Param("apprenticeId") Long apprenticeId);
}
