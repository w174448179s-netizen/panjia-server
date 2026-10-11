package com.panjia.received.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.panjia.received.domain.ReceivedContract;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 实收合同 Mapper。
 * <p>
 * 单表等值查询一律用 {@code LambdaQueryWrapper + BaseMapper.selectOne/selectCount}，
 * 不声明自定义方法；需要 JOIN / 聚合 / 动态 SQL 时再按仓库注解 SQL 规范加 @Select。
 */
@Mapper
public interface ReceivedContractMapper extends BaseMapper<ReceivedContract> {

    /**
     * 统计某合同号在实收合同表（<b>全局，不限期间/批次</b>）的去重非空订单号数量。
     * <p>用于实收-应收归属的退化闸门：新签 order_no 误填成合同号导致双键不中时，
     * 仅当新签事实与实收合同两边订单维度都唯一才允许退化按合同号匹配；
     * 全局口径防止同一合同的多笔订单分批到账时被重复归属。
     *
     * @param contractNo 合同号
     * @return 去重非空订单号数量
     */
    @Select("""
        SELECT COUNT(DISTINCT order_no)
        FROM pj_received_contract
        WHERE contract_no = #{contractNo}
          AND order_no IS NOT NULL AND order_no &lt;&gt; ''
        """)
    long countDistinctOrderNos(@Param("contractNo") String contractNo);
}
