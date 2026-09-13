package com.panjia.payroll.mapper;

import com.panjia.payroll.domain.PayrollDetail;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;

import java.util.List;

@Mapper
public interface PayrollDetailMapper extends BaseMapperPlus<PayrollDetail, PayrollDetail> {

    @Select("SELECT * FROM pj_payroll_detail WHERE batch_id = #{batchId} ORDER BY dept_id, employee_id")
    List<PayrollDetail> selectByBatchId(@Param("batchId") Long batchId);

    @Delete("DELETE FROM pj_payroll_detail WHERE batch_id = #{batchId}")
    int deleteByBatchId(@Param("batchId") Long batchId);
}
