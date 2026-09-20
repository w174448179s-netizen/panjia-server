package com.panjia.contracts.port;

import com.panjia.contracts.dto.PointsRuleDTO;

/**
 * 积分规则查询端口：积分域读取薪酬政策规则中的积分口径（等级阈值/扣点/晚提交罚款单价）。
 * <p>
 * 薪酬域（panjia-payroll）基于政策规则表（pj_payroll_policy_rule GLOBAL）实现，
 * 积分域（panjia-people）消费，避免积分计算口径硬编码。
 */
public interface PointsRuleQueryPort {

    /**
     * 读取当前生效的全局积分规则。
     *
     * @return 规则视图；无 GLOBAL 政策规则或解析失败时返回 null（消费方自行兜底默认口径）
     */
    PointsRuleDTO pointsRule();
}
