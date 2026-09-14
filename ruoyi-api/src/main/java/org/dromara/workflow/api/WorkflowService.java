package org.dromara.workflow.api;

import org.dromara.workflow.api.domain.CompleteTaskDTO;
import org.dromara.workflow.api.domain.StartProcessDTO;
import org.dromara.workflow.api.domain.StartProcessReturnDTO;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 通用 工作流服务
 *
 * @author may
 */
public interface WorkflowService {

    /**
     * 运行中的实例 删除程实例，删除历史记录，删除业务与流程关联信息
     *
     * @param businessIds 业务id
     * @return 结果
     */
    boolean deleteInstance(List<String> businessIds);

    /**
     * 获取当前流程状态
     *
     * @param taskId 任务id
     * @return 状态
     */
    String getBusinessStatusByTaskId(Long taskId);

    /**
     * 获取当前流程状态
     *
     * @param businessId 业务id
     * @return 状态
     */
    String getBusinessStatus(String businessId);

    /**
     * 设置流程变量
     *
     * @param instanceId 流程实例id
     * @param variable   流程变量
     */
    void setVariable(Long instanceId, Map<String, Object> variable);

    /**
     * 获取流程变量
     *
     * @param instanceId 流程实例id
     * @return 流程变量详情
     */
    Map<String, Object> instanceVariable(Long instanceId);

    /**
     * 按照业务id查询流程实例id
     *
     * @param businessId 业务id
     * @return 结果
     */
    Long getInstanceIdByBusinessId(String businessId);

    /**
     * 启动流程
     *
     * @param startProcess 参数
     * @return 启动后的流程实例与首任务信息
     */
    StartProcessReturnDTO startWorkFlow(StartProcessDTO startProcess);

    /**
     * 办理任务
     * 系统后台发起审批 无用户信息 需要忽略权限
     * completeTask.getVariables().put("ignore", true);
     *
     * @param completeTask 参数
     * @return 办理成功返回 {@code true}
     */
    boolean completeTask(CompleteTaskDTO completeTask);

    /**
     * 办理任务
     *
     * @param taskId  任务ID
     * @param message 办理意见
     * @return 办理成功返回 {@code true}
     */
    boolean completeTask(Long taskId, String message);

    /**
     * 启动流程并办理第一个任务
     *
     * @param startProcess 参数
     * @return 首节点办理成功返回 {@code true}
     */
    boolean startCompleteTask(StartProcessDTO startProcess);

    /**
     * 按业务 id 查询当前待办任务 ID（中间节点）。
     * <p>用于服务层自动路由/超时自动审批等场景：实例已结束或不存在时返回 {@code null}。</p>
     *
     * @param businessId 业务id
     * @return 当前待办任务 ID，无则返回 {@code null}
     */
    Long getCurrentTaskId(String businessId);

    /**
     * 按业务 id 查询当前待办节点编码。
     *
     * @param businessId 业务id
     * @return 当前节点编码（如 FINANCE/DIRECTOR），无待办任务时返回 {@code null}
     */
    String getCurrentNodeCode(String businessId);

    /**
     * 驳回当前待办任务（系统后台身份，忽略权限，驳回到流程申请人节点）。
     *
     * @param taskId  当前待办任务 ID
     * @param message 驳回意见
     * @return 办理成功返回 {@code true}
     */
    boolean rejectTask(Long taskId, String message);

    /**
     * 扫描指定节点集合上的待办任务，将创建时间超过 timeoutHours 小时的任务以系统身份自动通过。
     * <p>用于「总监超时自动审批」等可配置超时策略；timeoutHours &lt;= 0 时直接返回 0（不处理）。</p>
     *
     * @param nodeCodes    节点编码集合（如 rcv_director / capp_director / perf_director）
     * @param timeoutHours 超时时长（小时，&gt;0 生效）
     * @param message      自动通过意见
     * @return 实际自动办理的任务数
     */
    int autoCompleteTimeoutTasks(Set<String> nodeCodes, int timeoutHours, String message);
}
