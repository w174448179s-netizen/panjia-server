package com.panjia.people.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;
import com.panjia.people.domain.SalaryRecord;

/**
 * 员工算薪当前态物化记录 Mapper（刷新走 {@link #upsert}）。
 */
@Mapper
public interface SalaryRecordMapper extends BaseMapperPlus<SalaryRecord, SalaryRecord> {

    /**
     * 插入或刷新某员工的当前态记录（PostgreSQL ON CONFLICT 幂等 upsert）。
     *
     * @param record 当前态记录
     * @return 影响行数
     */
    @org.apache.ibatis.annotations.Insert("""
        INSERT INTO pj_people_salary_record
            (employee_id, dept_id, status, level_code, social_insured,
             housing_insured, commercial_insured, dormitory, is_part_time,
             mentor_employee_id, refresh_time)
        VALUES
            (#{employeeId}, #{deptId}, #{status}, #{levelCode}, #{socialInsured},
             #{housingInsured}, #{commercialInsured}, #{dormitory}, #{isPartTime},
             #{mentorEmployeeId}, NOW())
        ON CONFLICT (employee_id) DO UPDATE SET
            dept_id            = EXCLUDED.dept_id,
            status             = EXCLUDED.status,
            level_code         = EXCLUDED.level_code,
            social_insured     = EXCLUDED.social_insured,
            housing_insured    = EXCLUDED.housing_insured,
            commercial_insured = EXCLUDED.commercial_insured,
            dormitory          = EXCLUDED.dormitory,
            is_part_time       = EXCLUDED.is_part_time,
            mentor_employee_id = EXCLUDED.mentor_employee_id,
            refresh_time       = NOW()
        """)
    int upsert(SalaryRecord record);
}
