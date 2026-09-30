package com.panjia.contracts.dto;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 手工提交实收结果 DTO（跨域契约，panjia-contracts 叶子模块）。
 * <p>
 * 手工提交：按业务键选中合同 → 实收域以 PERF_EXPECT ACTIVE 应收为镜像
 * 在 pj_received_detail/contract 造实收明细 → 按订单号分组建实收审批单。
 * performance 域不再写 PERF_REAL 事实，全部经 ReceivedApplyPort 委托本结果。
 */
@Data
public class ManualReceivedSubmitResultDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 新建实收明细（rd）行数 */
    private int createdDetailCount;

    /** 新建实收审批单数（合并不计） */
    private int createdApplyCount;

    /** 跳过的 PERF_EXPECT 事实 ID + 原因（不存在/已作废/已有实收/插入失败等） */
    private Map<Long, String> skippedReasons = new LinkedHashMap<>();
}
