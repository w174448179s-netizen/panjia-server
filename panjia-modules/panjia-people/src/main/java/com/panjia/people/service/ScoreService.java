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

    /**
     * 手工新增积分记录（补录/修正）。
     * 数据来源标记 MANUAL；后续同员工同月份的导入同步会覆盖该记录（导入为准）。
     * 校验：员工存在、月份未锁定、同月无重复记录。
     */
    void create(Long employeeId, java.time.YearMonth scoreMonth, java.math.BigDecimal totalPoints,
                Integer attendDays, Integer lateSubmitCount);

    /**
     * 删除积分记录。仅在期间未锁定（非 SUBMITTED/APPROVED）时允许删除。
     */
    void delete(Long id);

    /**
     * 修改积分原始事实（总积分/出勤天数/晚提交次数）。
     * 用途：数据修正；晚提交处罚 = 晚提交次数 × 5 元/次，特殊情况（如谈单到深夜）
     * 经总监同意可减免，由人事调整晚提交次数，扣款随查询实时重算，审批快照定格供总监核对。
     * 平均积分/绩效等级/提成扣点为派生字段，随修改自动按新事实重算。
     * 仅在期间未锁定（非 SUBMITTED/APPROVED）时允许修改。
     *
     * @param id              积分记录 ID
     * @param totalPoints     总积分（≥0）
     * @param attendDays      出勤天数（≥0）
     * @param lateSubmitCount 晚提交次数（≥0）
     */
    void updateRawFacts(Long id, java.math.BigDecimal totalPoints, Integer attendDays, Integer lateSubmitCount);
}
