package com.panjia.payroll.mapper;

import com.panjia.payroll.domain.PayrollBatch;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;

@Mapper
public interface PayrollBatchMapper extends BaseMapperPlus<PayrollBatch, PayrollBatch> {

    /**
     * 行级悲观锁读取批次（SELECT FOR UPDATE），串行化同一批次的并发算薪：
     * 后到请求阻塞至前一事务提交后重读状态，被 assertCanCalculate 拒绝，避免并发重算互相覆盖。
     */
    @Select("SELECT * FROM pj_payroll_batch WHERE id = #{id} FOR UPDATE")
    PayrollBatch selectByIdForUpdate(@Param("id") Long id);
}
