package com.panjia.people.service;

import com.panjia.contracts.dto.ScoreSummarySyncDTO;
import com.panjia.contracts.port.PeopleScoreQueryPort;
import com.panjia.people.dto.ScoreQuery;
import com.panjia.people.dto.ScoreVO;
import org.dromara.common.core.domain.PageResult;
import org.dromara.common.mybatis.core.page.PageQuery;

import java.util.List;

/**
 * 绩效积分月度汇总服务：积分日报导入同步 + 明细查询。
 * <p>
 * 同时实现 {@link PeopleScoreQueryPort} 供薪酬域算薪读取绩效等级。
 * 积分数据全部来自导入同步（upsert 幂等），无人工登记入口。
 */
public interface ScoreService extends PeopleScoreQueryPort {

    /**
     * 导入批次归档后的积分月度同步（ScoreArchiveHandler 消费调用）。
     * 按工号匹配员工档案 upsert 积分表（同人同月覆盖），工号匹配失败的行跳过并告警；
     * 同步成功后该期间积分审批单自动失效回待提交（防止按旧数据算薪）。
     *
     * @param period    归属期间（YYYY-MM）
     * @param summaries 月度汇总（一人一行）
     */
    void syncScoreSummaries(String period, List<ScoreSummarySyncDTO> summaries);

    /** 管理端分页查询（人事/总监） */
    PageResult<ScoreVO> page(ScoreQuery query, PageQuery pageQuery);

    /** 明细查询 */
    ScoreVO getById(Long id);
}
