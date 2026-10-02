package com.panjia.commission.mapper;

import com.panjia.commission.domain.CommissionApplication;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;

import java.util.List;

/**
 * 结佣申请单 Mapper。
 */
@Mapper
public interface CommissionApplicationMapper extends BaseMapperPlus<CommissionApplication, CommissionApplication> {

    /** 有结佣申请单的期间（倒序），供前端默认选中最新有数据期间 */
    @Select("""
        SELECT period FROM pj_commission_application
        WHERE period IS NOT NULL
        GROUP BY period
        ORDER BY period DESC
        """)
    List<String> selectDistinctPeriods();
}
