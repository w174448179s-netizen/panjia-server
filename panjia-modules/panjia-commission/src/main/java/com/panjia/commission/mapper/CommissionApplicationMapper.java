package com.panjia.commission.mapper;

import com.panjia.commission.domain.CommissionApplication;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;

import java.util.Collection;
import java.util.List;

/**
 * 结佣申请单 Mapper。
 */
@Mapper
public interface CommissionApplicationMapper extends BaseMapperPlus<CommissionApplication, CommissionApplication> {

    /** 有结佣申请单的期间（倒序），可排除指定状态（终态单据不参与默认期间） */
    @Select("""
        <script>
        SELECT period FROM pj_commission_application
        WHERE period IS NOT NULL
        <if test="excludedStatuses != null and excludedStatuses.size() > 0">
          AND status NOT IN
          <foreach collection="excludedStatuses" item="s" open="(" separator="," close=")">#{s}</foreach>
        </if>
        GROUP BY period
        ORDER BY period DESC
        </script>
        """)
    List<String> selectDistinctPeriods(@Param("excludedStatuses") Collection<String> excludedStatuses);
}
