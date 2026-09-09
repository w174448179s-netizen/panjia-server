package com.panjia.people.mapper;

import com.panjia.people.domain.FactType;
import com.panjia.people.domain.SalaryFact;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;

import java.time.LocalDate;
import java.util.List;

/**
 * 算薪事实 Mapper。
 * <p>
 * 闭开区间协议 {@code [effective_date, expire_date)} 的取数与旧区间闭合。
 */
@Mapper
public interface SalaryFactMapper extends BaseMapperPlus<SalaryFact, SalaryFact> {

    /**
     * 查询在指定生效日仍处于开放状态（覆盖该日）的事实记录，用于 changeFact 时闭合旧区间。
     * <p>
     * 条件：effective_date &lt;= effect 且（expire_date 为空 或 effect &lt; expire_date）。
     *
     * @param employeeId 员工 ID
     * @param factType   事实类型
     * @param effect     生效日
     * @return 待闭合的事实记录
     */
    @Select("SELECT * FROM pj_people_salary_fact " +
        "WHERE employee_id = #{employeeId} AND fact_type = #{factType} " +
        "AND effective_date <= #{effect} " +
        "AND (expire_date IS NULL OR #{effect} < expire_date)")
    List<SalaryFact> selectActiveAt(@Param("employeeId") Long employeeId,
                                    @Param("factType") FactType factType,
                                    @Param("effect") LocalDate effect);

    /**
     * 取某员工某类事实在指定时点的最新值（闭开区间，按生效日倒序取第一条）。
     *
     * @param employeeId 员工 ID
     * @param factType   事实类型
     * @param point      取数时点
     * @return 事实值；无记录返回 null
     */
    @Select("SELECT value FROM pj_people_salary_fact " +
        "WHERE employee_id = #{employeeId} AND fact_type = #{factType} " +
        "AND effective_date <= #{point} " +
        "AND (expire_date IS NULL OR #{point} < expire_date) " +
        "ORDER BY effective_date DESC, create_time DESC LIMIT 1")
    String selectValueAt(@Param("employeeId") Long employeeId,
                         @Param("factType") FactType factType,
                         @Param("point") LocalDate point);

    /**
     * 批量取多员工某类事实在指定时点的最新值（算薪批次快照用）。
     *
     * @param employeeIds 员工 ID 集合
     * @param factType    事实类型
     * @param point       取数时点
     * @return 事实记录列表（每员工至多一条，DISTINCT ON 取最新）
     */
    @Select("""
        <script>
        SELECT DISTINCT ON (employee_id) * FROM pj_people_salary_fact
        WHERE fact_type = #{factType}
          AND effective_date &lt;= #{point}
          AND (expire_date IS NULL OR #{point} &lt; expire_date)
          AND employee_id IN
          <foreach collection="employeeIds" item="eid" open="(" separator="," close=")">
              #{eid}
          </foreach>
        ORDER BY employee_id, effective_date DESC, create_time DESC
        </script>
        """)
    List<SalaryFact> selectValuesAt(@Param("employeeIds") List<Long> employeeIds,
                                    @Param("factType") FactType factType,
                                    @Param("point") LocalDate point);

    /**
     * 闭合旧区间：将覆盖指定生效日的开放事实记录 expire_date 置为 effect 日。
     *
     * @param employeeId 员工 ID
     * @param factType   事实类型
     * @param effect     新生效日（旧区间的开区间终点）
     * @return 更新行数
     */
    @Update("UPDATE pj_people_salary_fact SET expire_date = #{effect} " +
        "WHERE employee_id = #{employeeId} AND fact_type = #{factType} " +
        "AND effective_date <= #{effect} " +
        "AND (expire_date IS NULL OR #{effect} < expire_date)")
    int closeActiveAt(@Param("employeeId") Long employeeId,
                      @Param("factType") FactType factType,
                      @Param("effect") LocalDate effect);
}
