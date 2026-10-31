package com.panjia.dingtalk.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.panjia.dingtalk.domain.DingTalkSyncLog;
import com.panjia.dingtalk.mapper.DingTalkSyncLogMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.domain.PageResult;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 钉钉同步日志服务（一次同步一行，先落 RUNNING 再回写结果）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DingTalkSyncLogService {

    /** 同步类型：考勤 */
    public static final String TYPE_ATTENDANCE = "ATTENDANCE";
    /** 同步类型：积分 */
    public static final String TYPE_SCORE = "SCORE";

    /** 触发方式：手动 */
    public static final String TRIGGER_MANUAL = "MANUAL";
    /** 触发方式：定时 */
    public static final String TRIGGER_SCHEDULE = "SCHEDULE";

    /** 状态：执行中 */
    public static final String STATUS_RUNNING = "RUNNING";
    /** 状态：全部成功 */
    public static final String STATUS_SUCCESS = "SUCCESS";
    /** 状态：部分成功（有跳过/失败） */
    public static final String STATUS_PARTIAL = "PARTIAL";
    /** 状态：失败 */
    public static final String STATUS_FAILED = "FAILED";

    private final DingTalkSyncLogMapper syncLogMapper;

    /**
     * 开始一次同步，先插入 RUNNING 记录。
     */
    public DingTalkSyncLog start(String syncType, String period, String triggerType, Long operatorId) {
        DingTalkSyncLog entity = new DingTalkSyncLog();
        entity.setSyncType(syncType);
        entity.setPeriod(period);
        entity.setTriggerType(triggerType);
        entity.setStatus(STATUS_RUNNING);
        entity.setTotalCount(0);
        entity.setSuccessCount(0);
        entity.setSkippedCount(0);
        entity.setFailedCount(0);
        entity.setOperatorId(operatorId);
        entity.setStartTime(LocalDateTime.now());
        syncLogMapper.insert(entity);
        return entity;
    }

    /**
     * 回写同步结果。
     */
    public void finish(DingTalkSyncLog entity, String status, int total, int success,
                       int skipped, int failed, String message) {
        if (entity == null) {
            return;
        }
        entity.setStatus(status);
        entity.setTotalCount(total);
        entity.setSuccessCount(success);
        entity.setSkippedCount(skipped);
        entity.setFailedCount(failed);
        entity.setMessage(message);
        entity.setEndTime(LocalDateTime.now());
        syncLogMapper.updateById(entity);
    }

    /**
     * 该期间是否已有指定类型的执行中同步（防止重复触发）。
     *
     * @param syncType 同步类型（ATTENDANCE / SCORE）
     * @param period   归属期间
     */
    public boolean hasRunning(String syncType, String period) {
        Long count = syncLogMapper.selectCount(new LambdaQueryWrapper<DingTalkSyncLog>()
            .eq(DingTalkSyncLog::getSyncType, syncType)
            .eq(DingTalkSyncLog::getPeriod, period)
            .eq(DingTalkSyncLog::getStatus, STATUS_RUNNING));
        return count != null && count > 0;
    }

    /**
     * 同步记录分页（syncType 为空表示全部）。
     */
    public PageResult<DingTalkSyncLog> page(String syncType, PageQuery pageQuery) {
        LambdaQueryWrapper<DingTalkSyncLog> wrapper = new LambdaQueryWrapper<DingTalkSyncLog>()
            .eq(StringUtils.isNotBlank(syncType), DingTalkSyncLog::getSyncType, syncType)
            .orderByDesc(DingTalkSyncLog::getStartTime)
            .orderByDesc(DingTalkSyncLog::getId);
        Page<DingTalkSyncLog> page = syncLogMapper.selectPage(pageQuery.build(), wrapper);
        return PageResult.build(page.getRecords(), page.getTotal());
    }
}
