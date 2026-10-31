package com.panjia.contracts.port;

import com.panjia.contracts.dto.ScoreDetailSyncDTO;

import java.util.List;

/**
 * 员工域积分同步端口（dingtalk → people）。
 * <p>
 * 钉钉积分日报日志按月拉取后，按 (员工, 日期) 写入每日明细：
 * 同月重跑整月覆盖钉钉来源的明细，月度汇总由明细全量重算。
 */
public interface PeopleScoreSyncPort {

    /**
     * 按期间同步钉钉积分每日明细（整月替换钉钉来源，其他来源的明细不受影响）。
     *
     * @param period  归属期间（YYYY-MM）
     * @param details 每日明细（一行一人一天，score 为当日积分合计）
     */
    void syncDingTalkScoreDetails(String period, List<ScoreDetailSyncDTO> details);
}
