package com.panjia.performance.mapper;

import com.panjia.performance.domain.ReceivedApply;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;

import java.util.List;

/**
 * 实收业绩审批单 Mapper。
 */
@Mapper
public interface ReceivedApplyMapper extends BaseMapperPlus<ReceivedApply, ReceivedApply> {

    /** 有实收审批单的期间（倒序），供前端默认选中最新有数据期间 */
    @Select("""
        SELECT period FROM pj_perf_received_apply
        WHERE period IS NOT NULL
        GROUP BY period
        ORDER BY period DESC
        """)
    List<String> selectDistinctPeriods();
}
