package com.panjia.payroll.mapper;

import com.panjia.payroll.domain.PayrollDetail;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;

import java.util.Collection;
import java.util.List;

@Mapper
public interface PayrollDetailMapper extends BaseMapperPlus<PayrollDetail, PayrollDetail> {

    @Select("SELECT * FROM pj_payroll_detail WHERE batch_id = #{batchId} ORDER BY dept_id, employee_id")
    List<PayrollDetail> selectByBatchId(@Param("batchId") Long batchId);

    /**
     * 按批次 + 员工 ID 集合查工资明细（合同号过滤用：先查业绩事实匹配员工集合再过滤）。
     */
    @Select("<script>SELECT * FROM pj_payroll_detail WHERE batch_id = #{batchId} " +
        "AND employee_id IN " +
        "<foreach collection='employeeIds' item='id' open='(' separator=',' close=')'>#{id}</foreach> " +
        "ORDER BY dept_id, employee_id</script>")
    List<PayrollDetail> selectByBatchIdAndEmployeeIds(@Param("batchId") Long batchId,
                                                      @Param("employeeIds") Collection<Long> employeeIds);

    /**
     * 按员工查全部工资明细（本人工资查询用），按期间、批次倒序。
     */
    @Select("SELECT * FROM pj_payroll_detail WHERE employee_id = #{employeeId} ORDER BY period DESC, batch_id DESC")
    List<PayrollDetail> selectByEmployeeId(@Param("employeeId") Long employeeId);

    /**
     * 查指定批次中某员工的工资明细（本人工资查询，服务层保证 employeeId 来自登录态解析）。
     */
    @Select("SELECT * FROM pj_payroll_detail WHERE batch_id = #{batchId} AND employee_id = #{employeeId} LIMIT 1")
    PayrollDetail selectByBatchAndEmployee(@Param("batchId") Long batchId, @Param("employeeId") Long employeeId);

    @Delete("DELETE FROM pj_payroll_detail WHERE batch_id = #{batchId}")
    int deleteByBatchId(@Param("batchId") Long batchId);

    /**
     * 查询上月工资明细中净发为负的记录（负工资结转）。
     * <p>
     * 仅返回 net &lt; 0 的记录，其 net 值即为待结转的负金额。
     *
     * @param prevPeriod 上月期间（YYYY-MM）
     * @return 负净发的工资明细列表
     */
    @Select("SELECT * FROM pj_payroll_detail WHERE period = #{prevPeriod} AND net < 0")
    List<PayrollDetail> selectNegativeNetByPeriod(@Param("prevPeriod") String prevPeriod);

    /**
     * 查询当年累计个税（当年之前所有月份的 tax 合计，按员工聚合）。
     *
     * @param yearStart 当年起始月（YYYY-01）
     * @param period    当前期间（YYYY-MM，排除）
     * @return 工资明细列表（仅 employee_id 和 tax 有值）
     */
    @Select("SELECT employee_id AS employeeId, COALESCE(SUM(tax), 0) AS tax " +
        "FROM pj_payroll_detail " +
        "WHERE period >= #{yearStart} AND period < #{period} " +
        "GROUP BY employee_id")
    List<PayrollDetail> selectCumulativeTax(@Param("yearStart") String yearStart,
                                            @Param("period") String period);

    /**
     * 查询当年累计应纳税所得额（当年之前所有月份的 gross - deduct 合计，按员工聚合）。
     *
     * @param yearStart 当年起始月（YYYY-01）
     * @param period    当前期间（YYYY-MM，排除）
     * @return 工资明细列表（仅 employee_id、gross、deduct 有值）
     */
    @Select("SELECT employee_id AS employeeId, COALESCE(SUM(gross - deduct), 0) AS gross, " +
        "COALESCE(SUM(deduct), 0) AS deduct " +
        "FROM pj_payroll_detail " +
        "WHERE period >= #{yearStart} AND period < #{period} " +
        "GROUP BY employee_id")
    List<PayrollDetail> selectCumulativeTaxable(@Param("yearStart") String yearStart,
                                                  @Param("period") String period);
}
