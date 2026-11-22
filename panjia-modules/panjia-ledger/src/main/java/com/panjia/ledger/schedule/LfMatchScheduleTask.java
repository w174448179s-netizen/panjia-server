package com.panjia.ledger.schedule;

import com.panjia.ledger.mapper.LfStatementRowMapper;
import com.panjia.ledger.service.LfMatchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.redis.utils.RedisUtils;
import org.dromara.system.api.ConfigService;
import org.redisson.api.RLock;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.TimeZone;

/**
 * 理房通流水每日定时匹配。
 * <p>每天按参数管理中的 cron 重新执行一次全量匹配，用于补触发新签导入、历史流水变更等场景。
 * 开关与 cron 均在「系统管理 → 参数设置」维护，修改后无需重启；非法 cron 回退默认值。
 * 通过 Redisson 分布式锁保证多实例下只有一个实例执行。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LfMatchScheduleTask implements SchedulingConfigurer {

    /** 单次事务处理的合同数，避免全量合同在一个长事务中 */
    private static final int BATCH_SIZE = 200;

    private static final String LOCK_KEY = "panjia:lf:match:schedule-lock";

    /** 参数管理键名：是否启用每日自动匹配 */
    public static final String CONFIG_ENABLED = "panjia.lf.match.schedule.enabled";

    /** 参数管理键名：每日自动匹配 cron */
    public static final String CONFIG_CRON = "panjia.lf.match.schedule.cron";

    /** 默认每天 00:00 */
    private static final String DEFAULT_CRON = "0 0 0 * * ?";

    private static final TimeZone ZONE = TimeZone.getTimeZone("Asia/Shanghai");

    private final LfStatementRowMapper rowMapper;
    private final LfMatchService lfMatchService;
    private final ConfigService configService;

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        registrar.addTriggerTask(this::runMatch, triggerContext ->
            new CronTrigger(resolveCron(), ZONE).nextExecution(triggerContext));
    }

    /**
     * 执行一次全量匹配。
     * <p>每次触发时重新读取开关，关闭时直接跳过。</p>
     */
    private void runMatch() {
        if (!isEnabled()) {
            log.info("[理房通匹配] 参数管理已关闭每日自动匹配，跳过");
            return;
        }
        RLock lock = RedisUtils.getClient().getLock(LOCK_KEY);
        if (!lock.tryLock()) {
            log.info("[理房通匹配] 锁被占用，其他实例正在执行，本实例跳过");
            return;
        }
        try {
            List<String> contractNos = rowMapper.selectAllMatchableContractNos();
            if (contractNos.isEmpty()) {
                log.info("[理房通匹配] 无可匹配合同，跳过");
                return;
            }

            int contractCount = 0;
            int matched = 0;
            int diff = 0;
            int notFound = 0;
            int pendingSignRole = 0;
            for (int i = 0; i < contractNos.size(); i += BATCH_SIZE) {
                List<String> chunk = contractNos.subList(i, Math.min(i + BATCH_SIZE, contractNos.size()));
                LfMatchService.Stats stats = lfMatchService.matchContracts(chunk, null);
                contractCount += stats.getContractCount();
                matched += stats.getMatched();
                diff += stats.getDiff();
                notFound += stats.getNotFound();
                pendingSignRole += stats.getPendingSignRole();
            }
            log.info("[理房通匹配] 定时任务完成，合同 {}，一致 {}，差异 {}，未找到 {}，待补角色人 {}",
                contractCount, matched, diff, notFound, pendingSignRole);
        } catch (Exception e) {
            log.error("[理房通匹配] 定时任务执行失败：{}", e.getMessage(), e);
        } finally {
            if (lock.isHeldByCurrentThread()) {
                try {
                    lock.unlock();
                } catch (Exception e) {
                    log.warn("[理房通匹配] 释放分布式锁异常", e);
                }
            }
        }
    }

    /** 读取开关，未配置时默认开启 */
    private boolean isEnabled() {
        String value = configService.getConfigValue(CONFIG_ENABLED);
        return !StringUtils.hasText(value) || Boolean.parseBoolean(value.trim());
    }

    /** 读取 cron，未配置或非法时回退默认值 */
    private String resolveCron() {
        String cron = configService.getConfigValue(CONFIG_CRON);
        if (!StringUtils.hasText(cron)) {
            return DEFAULT_CRON;
        }
        String trimmed = cron.trim();
        try {
            new CronTrigger(trimmed, ZONE);
            return trimmed;
        } catch (IllegalArgumentException e) {
            log.warn("[理房通匹配] 参数 {} 的 cron 非法（{}），回退默认 {}", CONFIG_CRON, trimmed, DEFAULT_CRON);
            return DEFAULT_CRON;
        }
    }
}
