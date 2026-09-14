package org.dromara.workflow.job;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.system.api.ConfigService;
import org.dromara.workflow.api.WorkflowService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * 总监超时自动审批定时任务（工作流可配置项 §5）。
 * <p>
 * 参数 {@code panjia.flow.director_timeout_hours}（sys_config）：
 * &gt;0 时，各业务流程「总监审批」节点上滞留超过该时长的待办任务由系统自动通过；
 * 0 或未配置表示不自动审批。每 10 分钟扫描一次。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DirectorTimeoutJob {

    private static final String CONFIG_TIMEOUT_HOURS = "panjia.flow.director_timeout_hours";

    /** 全部业务流程的总监审批节点编码 */
    private static final Set<String> DIRECTOR_NODES = Set.of(
        "perf_director",      // 业绩调整
        "rcv_director",       // 实收业绩审批
        "capp_director",      // 结佣申请
        "commission_director",// 结佣调整（旧流程）
        "adjust_director",    // 通用调整
        "bonus_director",     // 奖金
        "supplement_director" // 补差
    );

    private final WorkflowService workflowService;
    private final ConfigService configService;

    @Scheduled(fixedDelay = 10 * 60 * 1000L, initialDelay = 60 * 1000L)
    public void autoApproveTimeoutDirectorTasks() {
        Integer hours;
        try {
            hours = configService.getConfigInt(CONFIG_TIMEOUT_HOURS);
        } catch (Exception e) {
            log.debug("[总监超时审批] 读取配置失败，跳过本轮：{}", e.getMessage());
            return;
        }
        if (hours == null || hours <= 0) {
            return;
        }
        try {
            int done = workflowService.autoCompleteTimeoutTasks(
                DIRECTOR_NODES, hours, "总监审批超时 " + hours + " 小时，系统自动通过");
            if (done > 0) {
                log.info("[总监超时审批] 本轮自动通过任务数={}, 超时阈值={}小时", done, hours);
            }
        } catch (Exception e) {
            log.error("[总监超时审批] 定时扫描失败", e);
        }
    }
}
