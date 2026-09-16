package org.dromara.workflow.listener;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.convert.Convert;
import cn.hutool.core.lang.Dict;
import cn.hutool.core.lang.TypeReference;
import cn.hutool.core.map.MapUtil;
import cn.hutool.core.util.ObjectUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.enums.BusinessStatusEnum;
import org.dromara.common.core.utils.SpringUtils;
import org.dromara.common.core.utils.StreamUtils;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.json.utils.JsonUtils;
import org.dromara.system.api.UserService;
import org.dromara.warm.flow.core.FlowEngine;
import org.dromara.warm.flow.core.dto.FlowParams;
import org.dromara.warm.flow.core.entity.Definition;
import org.dromara.warm.flow.core.entity.Instance;
import org.dromara.warm.flow.core.entity.Task;
import org.dromara.warm.flow.core.listener.GlobalListener;
import org.dromara.warm.flow.core.listener.ListenerVariable;
import org.dromara.workflow.common.ConditionalOnEnable;
import org.dromara.workflow.common.constant.FlowConstant;
import org.dromara.workflow.common.enums.MessageTypeEnum;
import org.dromara.workflow.common.enums.TaskStatusEnum;
import org.dromara.workflow.domain.bo.BackProcessBo;
import org.dromara.workflow.domain.bo.CompleteTaskBo;
import org.dromara.workflow.domain.bo.FlowCopyBo;
import org.dromara.workflow.domain.vo.FlowTaskVo;
import org.dromara.workflow.domain.vo.NodeExtVo;
import org.dromara.workflow.event.WorkflowCopyEvent;
import org.dromara.workflow.event.WorkflowResultMessageEvent;
import org.dromara.workflow.event.WorkflowTaskMessageEvent;
import org.dromara.workflow.handler.FlowProcessEventHandler;
import org.dromara.workflow.service.IFlwCommonService;
import org.dromara.workflow.service.IFlwInstanceService;
import org.dromara.workflow.service.IFlwNodeExtService;
import org.dromara.workflow.service.IFlwTaskService;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 工作流全局监听器，处理任务流转中的扩展变量、消息和事件发布。
 *
 * @author may
 */
@ConditionalOnEnable
@Component
@Slf4j
@RequiredArgsConstructor
public class WorkflowGlobalListener implements GlobalListener {

    private final IFlwTaskService flwTaskService;
    private final IFlwInstanceService flwInstanceService;
    private final FlowProcessEventHandler flowProcessEventHandler;
    private final IFlwCommonService flwCommonService;
    private final IFlwNodeExtService nodeExtService;
    private final UserService userService;

    /**
     * 任务创建回调：超时自动审批调度（T-03）。
     * <p>
     * 设计依据：《审批集成设计说明 V1.0》§六 坑①——节点创建监听器启动定时器，
     * 任务滞留超 {@code ext.autoApproval} 小时后系统自动办理。
     * <ul>
     *   <li>读取节点 {@code ext} 中 {@code code = "AutoApproval"} 的项；
     *     value 格式 {@code hours=72,skipType=PASS} 或简写 {@code 72}（仅 hours，skipType 默认 PASS）；</li>
     *   <li>未配置 autoApproval / hours ≤ 0 时不启动定时器（与设计文档 §三 #2「超时未启用时仅提醒」语义一致）；</li>
     *   <li>应用关停时定时器丢失，由 {@link org.dromara.workflow.job.DirectorTimeoutJob} 全局扫描兜底；</li>
     *   <li>多节点部署时只有一个节点收到 create 回调并启动定时器，与设计文档「创建监听器」语义一致。</li>
     * </ul>
     * <p>
     * 配置示例（节点 ext JSON）：
     * <pre>{@code
     * [{"code":"AutoApproval","value":"hours=72,skipType=PASS"}]
     * }</pre>
     *
     * @param listenerVariable 监听器变量
     */
    @Override
    public void create(ListenerVariable listenerVariable) {
        Task task = listenerVariable.getTask();
        if (task == null || task.getId() == null) {
            return;
        }
        String ext = listenerVariable.getNode() != null ? listenerVariable.getNode().getExt() : null;
        if (StringUtils.isBlank(ext)) {
            return;
        }
        // 解析 ext JSON 找 code = "AutoApproval" 的项
        List<Dict> extMap = JsonUtils.parseArrayMap(ext);
        if (CollUtil.isEmpty(extMap)) {
            return;
        }
        for (Dict item : extMap) {
            if (!"AutoApproval".equals(item.getStr("code"))) {
                continue;
            }
            String value = item.getStr("value");
            if (StringUtils.isBlank(value)) {
                continue;
            }
            AutoApprovalConfig cfg = parseAutoApproval(value);
            if (cfg == null || cfg.hours <= 0) {
                continue;
            }
            scheduleAutoApproval(task.getId(), cfg);
            log.info("[工作流-超时自动审批] 节点创建监听器注册定时器：taskId={}, nodeCode={}, hours={}, skipType={}",
                task.getId(), task.getNodeCode(), cfg.hours, cfg.skipType);
        }
    }

    /**
     * 解析 AutoApproval value 字符串。
     * <p>支持两种格式：
     * <ul>
     *   <li>{@code hours=72,skipType=PASS} 完整格式</li>
     *   <li>{@code 72} 简写（仅 hours，skipType 默认 PASS）</li>
     * </ul>
     */
    private AutoApprovalConfig parseAutoApproval(String value) {
        String trimmed = value.trim();
        // 简写：纯数字
        if (trimmed.matches("\\d+(\\.\\d+)?")) {
            return new AutoApprovalConfig(Double.parseDouble(trimmed), "PASS");
        }
        // 完整格式：hours=72,skipType=PASS
        String hours = null;
        String skipType = "PASS";
        for (String kv : trimmed.split(",")) {
            String[] pair = kv.trim().split("=", 2);
            if (pair.length != 2) {
                continue;
            }
            String k = pair[0].trim();
            String v = pair[1].trim();
            if ("hours".equalsIgnoreCase(k)) {
                hours = v;
            } else if ("skipType".equalsIgnoreCase(k)) {
                skipType = v.toUpperCase();
            }
        }
        if (hours == null || !hours.matches("\\d+(\\.\\d+)?")) {
            return null;
        }
        return new AutoApprovalConfig(Double.parseDouble(hours), skipType);
    }

    /**
     * 注册超时自动审批定时任务。
     * <p>用 Spring {@link TaskScheduler} 延时调度，到点检查任务仍未办理则系统自动办理。
     * 应用关停时定时器丢失，{@link org.dromara.workflow.job.DirectorTimeoutJob} 兜底。
     */
    private void scheduleAutoApproval(Long taskId, AutoApprovalConfig cfg) {
        try {
            TaskScheduler scheduler = SpringUtils.getBean(TaskScheduler.class);
            Instant triggerTime = Instant.now().plusSeconds((long) (cfg.hours * 3600));
            scheduler.schedule(() -> {
                try {
                    executeAutoApproval(taskId, cfg);
                } catch (Exception e) {
                    log.warn("[工作流-超时自动审批] 定时任务执行失败：taskId={}, reason={}",
                        taskId, e.getMessage());
                }
            }, triggerTime);
        } catch (Exception e) {
            // TaskScheduler Bean 缺失或调度失败，由 DirectorTimeoutJob 兜底
            log.warn("[工作流-超时自动审批] 注册定时器失败，由 DirectorTimeoutJob 兜底：taskId={}, reason={}",
                taskId, e.getMessage());
        }
    }

    /**
     * 执行超时自动审批：检查任务仍待办 + 按 skipType 分流 PASS/REJECT。
     */
    private void executeAutoApproval(Long taskId, AutoApprovalConfig cfg) {
        FlowTaskVo task = flwTaskService.selectById(taskId);
        if (task == null) {
            // 任务已办理或不复存在，跳过
            log.info("[工作流-超时自动审批] 任务已办理或不复存在，跳过：taskId={}", taskId);
            return;
        }
        String message = "超时自动审批 " + cfg.hours + " 小时，系统自动通过";
        if ("REJECT".equalsIgnoreCase(cfg.skipType)) {
            message = "超时自动驳回 " + cfg.hours + " 小时，系统自动驳回";
            BackProcessBo bo = new BackProcessBo();
            bo.setTaskId(taskId);
            bo.setMessage(message);
            bo.setMessageType(Collections.singletonList(MessageTypeEnum.SYSTEM_MESSAGE.getCode()));
            flwTaskService.backProcess(bo);
            log.info("[工作流-超时自动审批] 超时自动驳回执行：taskId={}, nodeCode={}",
                taskId, task.getNodeCode());
            return;
        }
        // 默认 PASS
        CompleteTaskBo taskBo = new CompleteTaskBo();
        taskBo.setTaskId(taskId);
        taskBo.setMessage(message);
        taskBo.setMessageType(Collections.singletonList(MessageTypeEnum.SYSTEM_MESSAGE.getCode()));
        taskBo.getVariables().put("ignore", true);
        flwTaskService.completeTask(taskBo);
        log.info("[工作流-超时自动审批] 超时自动通过执行：taskId={}, nodeCode={}",
            taskId, task.getNodeCode());
    }

    /** AutoApproval 配置项。 */
    private static class AutoApprovalConfig {
        final double hours;
        final String skipType;
        AutoApprovalConfig(double hours, String skipType) {
            this.hours = hours;
            this.skipType = skipType;
        }
    }

    /**
     * 任务开始办理时解析节点扩展配置。
     *
     * @param listenerVariable 监听器变量
     */
    @Override
    public void start(ListenerVariable listenerVariable) {
        String ext = listenerVariable.getNode().getExt();
        if (StringUtils.isNotBlank(ext)) {
            Map<String, Object> variable = listenerVariable.getVariable();
            if (CollUtil.isEmpty(variable)) {
                variable = new HashMap<>();
            }
            NodeExtVo nodeExt = nodeExtService.parseNodeExt(ext, variable);
            Set<String> copyList = nodeExt.getCopySettings();
            if (CollUtil.isNotEmpty(copyList)) {
                List<Long> userIds = StreamUtils.toList(copyList, Convert::toLong);
                Map<Long, String> nickNameMap = userService.selectUserNicksByIds(userIds);
                List<FlowCopyBo> list = StreamUtils.toList(copyList, x -> {
                    FlowCopyBo bo = new FlowCopyBo();
                    Long id = Convert.toLong(x);
                    bo.setUserId(id);
                    bo.setNickName(nickNameMap.getOrDefault(id, StringUtils.EMPTY));
                    return bo;
                });
                variable.put(FlowConstant.FLOW_COPY_LIST, list);
            }
            if (CollUtil.isNotEmpty(nodeExt.getVariables())) {
                variable.putAll(nodeExt.getVariables());
            }
        }
    }

    /**
     * 任务分派时动态调整办理权限。
     *
     * @param listenerVariable 监听器变量
     */
    @Override
    public void assignment(ListenerVariable listenerVariable) {
        Map<String, Object> variable = listenerVariable.getVariable();
        List<Task> nextTasks = listenerVariable.getNextTasks();
        FlowParams flowParams = listenerVariable.getFlowParams();
        Definition definition = listenerVariable.getDefinition();
        Instance instance = listenerVariable.getInstance();
        String applyNodeCode = flwCommonService.applyNodeCode(definition.getId());
        String hisStatus = flowParams != null ? flowParams.getHisStatus() : null;

        for (Task flowTask : nextTasks) {
            String nodeCode = flowTask.getNodeCode();

            // 处理办理或退回时指定办理人的情况
            if (TaskStatusEnum.PASS.getStatus().equals(hisStatus)) {
                processTaskPermission(variable, flowTask, hisStatus);
            } else if (TaskStatusEnum.BACK.getStatus().equals(hisStatus)) {
                processTaskPermission(variable, flowTask, hisStatus);
            }

            // 如果是申请节点，则把启动人添加到办理人
            if (nodeCode.equals(applyNodeCode) && StringUtils.isNotBlank(instance.getCreateBy())) {
                flowTask.setPermissionList(List.of(instance.getCreateBy()));
            }
        }
    }

    /**
     * 处理任务权限设置
     *
     * @param variable   变量集合
     * @param flowTask   流程任务
     * @param taskStatus 任务状态
     */
    private void processTaskPermission(Map<String, Object> variable, Task flowTask, String taskStatus) {
        String nodeKey = taskStatus + StringUtils.COLON + flowTask.getNodeCode();

        // 检查是否存在状态相关的变量
        if (!variable.containsKey(nodeKey)) {
            return;
        }

        // 获取用户ID字符串
        Object userIdsObj = variable.get(nodeKey);
        if (userIdsObj == null) {
            return;
        }

        String userIds = userIdsObj.toString();
        if (StringUtils.isBlank(userIds)) {
            return;
        }

        // 分割用户ID并设置权限列表
        List<String> userIdList = StringUtils.str2List(userIds, StringUtils.SEPARATOR, true, true);
        if (CollUtil.isNotEmpty(userIdList)) {
            flowTask.setPermissionList(userIdList);
            if (TaskStatusEnum.PASS.getStatus().equals(taskStatus)) {
                // 办理指定人变量只消费一次；驳回指定人变量需要保留给后续重复驳回。
                variable.remove(nodeKey);
                FlowEngine.insService().removeVariables(flowTask.getInstanceId(), nodeKey);
            }
        }
    }

    /**
     * 任务完成后发布流程事件并处理抄送和通知。
     *
     * @param listenerVariable 监听器变量
     */
    @Override
    public void finish(ListenerVariable listenerVariable) {
        Instance instance = listenerVariable.getInstance();
        Definition definition = listenerVariable.getDefinition();
        Task task = listenerVariable.getTask();
        List<Task> nextTasks = listenerVariable.getNextTasks();
        Map<String, Object> params = new HashMap<>();
        FlowParams flowParams = listenerVariable.getFlowParams();
        Map<String, Object> variable = new HashMap<>();
        if (ObjectUtil.isNotNull(flowParams)) {
            // 历史任务扩展(通常为附件)
            params.put("hisTaskExt", flowParams.getHisTaskExt());
            // 办理人
            params.put("handler", flowParams.getHandler());
            // 办理意见
            params.put("message", flowParams.getMessage());
            variable = flowParams.getVariable();
        }
        //申请人提交事件
        Boolean submit = MapUtil.getBool(variable, FlowConstant.SUBMIT);
        if (submit != null && submit) {
            String status = determineFlowStatus(instance);
            flowProcessEventHandler.processHandler(definition.getFlowCode(), instance, status, variable, true);
        } else {
            // 判断流程状态（发布：撤销，退回，作废，终止，已完成事件）
            String status = determineFlowStatus(instance);
            if (StringUtils.isNotBlank(status)) {
                flowProcessEventHandler.processHandler(definition.getFlowCode(), instance, status, params, false);
                notifyInitiatorIfNeeded(definition, instance, status, variable);
            }
            if (!BusinessStatusEnum.initialState(instance.getFlowStatus())) {
                if (task != null && CollUtil.isNotEmpty(nextTasks) && nextTasks.size() == 1
                    && flwCommonService.applyNodeCode(definition.getId()).equals(nextTasks.getFirst().getNodeCode())) {
                    // 如果为画线指定驳回 线条指定为驳回 驳回得节点为申请人节点 则修改流程状态为退回
                    flowProcessEventHandler.processHandler(definition.getFlowCode(), instance, BusinessStatusEnum.BACK.getStatus(), params, false);
                    notifyInitiatorIfNeeded(definition, instance, BusinessStatusEnum.BACK.getStatus(), variable);
                    // 修改流程实例状态
                    instance.setFlowStatus(BusinessStatusEnum.BACK.getStatus());
                    FlowEngine.insService().updateById(instance);
                }
            }
        }
        //发布任务事件
        if (CollUtil.isNotEmpty(nextTasks)) {
            for (Task nextTask : nextTasks) {
                flowProcessEventHandler.processTaskHandler(definition.getFlowCode(), instance, nextTask, params);
            }
        }
        if (ObjectUtil.isNull(flowParams)) {
            return;
        }
        // 只有办理或者退回的时候才执行消息通知和抄送
        if (!TaskStatusEnum.isPassOrBack(flowParams.getHisStatus())) {
            return;
        }
        if (ObjectUtil.isNull(variable)) {
            return;
        }

        if (variable.containsKey(FlowConstant.FLOW_COPY_LIST)) {
            List<FlowCopyBo> flowCopyList = MapUtil.get(variable, FlowConstant.FLOW_COPY_LIST, new TypeReference<>() {
            });
            // 添加抄送人
            SpringUtils.context().publishEvent(new WorkflowCopyEvent(task, flowCopyList));
        }
        if (variable.containsKey(FlowConstant.MESSAGE_TYPE)) {
            List<String> messageType = MapUtil.get(variable, FlowConstant.MESSAGE_TYPE, new TypeReference<>() {
            });
            String notice = MapUtil.getStr(variable, FlowConstant.MESSAGE_NOTICE);
            // 退回到申请人时只保留“已退回”结果消息，避免再追加一条“新的待办”形成重复提醒。
            if (shouldSendTaskMessage(flowParams, definition, nextTasks)) {
                SpringUtils.context().publishEvent(new WorkflowTaskMessageEvent(definition.getFlowName(), instance.getId(), messageType, notice));
            }
        }
        FlowEngine.insService().removeVariables(instance.getId(),
            FlowConstant.FLOW_COPY_LIST,
            FlowConstant.MESSAGE_TYPE,
            FlowConstant.MESSAGE_NOTICE,
            FlowConstant.SUBMIT
        );
    }

    /**
     * 判断是否需要发送后续待办消息。
     *
     * @param flowParams 流程参数
     * @param definition 流程定义
     * @param nextTasks  后续任务列表
     * @return 是否发送待办消息
     */
    private boolean shouldSendTaskMessage(FlowParams flowParams, Definition definition, List<Task> nextTasks) {
        if (flowParams == null || !TaskStatusEnum.BACK.getStatus().equals(flowParams.getHisStatus())) {
            return true;
        }
        if (CollUtil.isEmpty(nextTasks) || nextTasks.size() != 1) {
            return true;
        }
        // 只有“退回到申请人”场景需要拦截待办提醒，其余退回/流转仍然保留待办消息。
        String applyNodeCode = flwCommonService.applyNodeCode(definition.getId());
        return !StringUtils.equals(applyNodeCode, nextTasks.getFirst().getNodeCode());
    }

    /**
     * 在流程完成或退回时通知发起人。
     *
     * @param definition 流程定义
     * @param instance   流程实例
     * @param status     业务状态
     * @param variable   流程变量
     */
    private void notifyInitiatorIfNeeded(Definition definition, Instance instance, String status, Map<String, Object> variable) {
        if (!StringUtils.equalsAny(status, BusinessStatusEnum.FINISH.getStatus(), BusinessStatusEnum.BACK.getStatus())) {
            return;
        }
        if (StringUtils.isBlank(instance.getCreateBy())) {
            return;
        }
        // 已完成、已退回这类结果消息只发给发起人，不再混入处理人待办消息。
        List<String> messageType = null;
        if (MapUtil.isNotEmpty(variable) && variable.containsKey(FlowConstant.MESSAGE_TYPE)) {
            messageType = MapUtil.get(variable, FlowConstant.MESSAGE_TYPE, new TypeReference<>() {
            });
        }
        SpringUtils.context().publishEvent(new WorkflowResultMessageEvent(definition.getFlowName(), status, instance.getCreateBy(), messageType));
    }

    /**
     * 根据流程实例确定最终状态
     *
     * @param instance 流程实例
     * @return 流程最终状态
     */
    private String determineFlowStatus(Instance instance) {
        String flowStatus = instance.getFlowStatus();
        if (StringUtils.isNotBlank(flowStatus) && BusinessStatusEnum.initialState(flowStatus)) {
            log.info("流程实例当前状态: {}", flowStatus);
            return flowStatus;
        } else {
            Long instanceId = instance.getId();
            if (flwTaskService.isTaskEnd(instanceId)) {
                String status = BusinessStatusEnum.FINISH.getStatus();
                // 更新流程状态为已完成
                flwInstanceService.updateStatus(instanceId, status);
                log.info("流程已结束，状态更新为: {}", status);
                return status;
            }
            return null;
        }
    }

}
