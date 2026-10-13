package com.panjia.received.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.panjia.received.domain.ReceivedDetail;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.math.BigDecimal;
import java.util.List;

/**
 * 实收明细 Mapper。
 * <p>
 * 单表等值查询一律用 {@code LambdaQueryWrapper + BaseMapper.selectOne/selectCount}，
 * 不声明自定义方法；需要 JOIN / 聚合 / 动态 SQL 时再按仓库注解 SQL 规范加 @Select。
 */
@Mapper
public interface ReceivedDetailMapper extends BaseMapper<ReceivedDetail> {

    /**
     * 按员工口径合计实收金额：指定单据集合内该员工的 ACTIVE 明细合计。
     * <p>
     * 用于实收明细列表按员工筛选时的汇总条——单据级 received_amount 含同合同其他
     * 角色人金额，员工筛选时必须只统计该员工自己的明细。
     * 员工匹配口径与列表筛选一致：employee_id 直连，为空时按外部工号匹配员工主数据。
     */
    @Select("""
        <script>
        SELECT COALESCE(SUM(rd.performance_amount), 0)
        FROM pj_received_detail rd
        JOIN pj_people_employee e ON (e.employee_id = rd.employee_id
           OR (NOT EXISTS (SELECT 1 FROM pj_people_employee x WHERE x.employee_id = rd.employee_id)
               AND e.employee_code = rd.employee_external_code))
        WHERE rd.detail_status = 'ACTIVE'
          AND e.employee_id = #{employeeId}
          AND rd.received_apply_id IN
          <foreach collection="applyIds" item="id" open="(" separator="," close=")">#{id}</foreach>
        </script>
        """)
    BigDecimal sumAmountByApplyIdsAndEmployee(@Param("applyIds") List<Object> applyIds,
                                              @Param("employeeId") Long employeeId);
}

