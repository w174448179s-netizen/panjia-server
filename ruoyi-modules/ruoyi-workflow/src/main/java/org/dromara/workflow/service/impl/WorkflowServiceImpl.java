package org.dromara.workflow.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.ObjectUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.warm.flow.core.FlowEngine;
import org.dromara.warm.flow.core.entity.Task;
import org.dromara.warm.flow.orm.entity.FlowInstance;
import org.dromara.warm.flow.orm.entity.FlowTask;
import org.dromara.workflow.api.WorkflowService;
import org.dromara.workflow.api.domain.CompleteTaskDTO;
import org.dromara.workflow.api.domain.StartProcessDTO;
import org.dromara.workflow.api.domain.StartProcessReturnDTO;
import org.dromara.workflow.common.ConditionalOnEnable;
import org.dromara.workflow.common.enums.MessageTypeEnum;
import org.dromara.workflow.domain.FlowInstanceBizExt;
import org.dromara.workflow.domain.bo.BackProcessBo;
import org.dromara.workflow.domain.bo.CompleteTaskBo;
import org.dromara.workflow.domain.bo.StartProcessBo;
import org.dromara.workflow.service.IFlwCommonService;
import org.dromara.workflow.service.IFlwInstanceService;
import org.dromara.workflow.service.IFlwTaskService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 通用 工作流服务实现
 *
 * @author may
 */
@Slf4j
@ConditionalOnEnable
@RequiredArgsConstructor
@Service
public class WorkflowServiceImpl implements WorkflowService {

    private final IFlwInstanceService flwInstanceService;
    private final IFlwTaskService flwTaskService;
    private final IFlwCommonService flwCommonService;

    /**
     * 删除流程实例
     *
     * @param businessIds 业务id
     * @return 结果
     */
    @Override
    public boolean deleteInstance(List<String> businessIds) {
        return flwInstanceService.deleteByBusinessIds(businessIds);
    }

    /**
     * 系统级删除流程实例（忽略权限校验，用于无用户上下文场景）
     *
     * @param businessIds 业务id
     * @return 结果
     */
    @Override
    public boolean deleteInstanceSys(List<String> businessIds) {
        return flwInstanceService.deleteByBusinessIdsSys(businessIds);
    }

    /**
     * 获取当前流程状态
     *
     * @param taskId 任务id
     * @return 任务关联流程实例的业务状态，未查询到时返回空字符串
     */
    @Override
    public String getBusinessStatusByTaskId(Long taskId) {
        FlowInstance flowInstance = flwInstanceService.selectByTaskId(taskId);
        return ObjectUtil.isNotNull(flowInstance) ? flowInstance.getFlowStatus() : StringUtils.EMPTY;
    }

    /**
     * 获取当前流程状态
     *
     * @param businessId 业务id
     * @return 业务单据对应的流程状态，未查询到时返回空字符串
     */
    @Override
    public String getBusinessStatus(String businessId) {
        FlowInstance flowInstance = flwInstanceService.selectInstByBusinessId(businessId);
        return ObjectUtil.isNotNull(flowInstance) ? flowInstance.getFlowStatus() : StringUtils.EMPTY;
    }

    /**
     * 设置流程变量
     *
     * @param instanceId 流程实例id
     * @param variables  流程变量
     */
    @Override
    public void setVariable(Long instanceId, Map<String, Object> variables) {
        flwInstanceService.setVariable(instanceId, variables);
    }

    /**
     * 获取流程变量
     *
     * @param instanceId 流程实例id
     * @return 实例变量信息
     */
    @Override
    public Map<String, Object> instanceVariable(Long instanceId) {
        return flwInstanceService.instanceVariable(instanceId);
    }

    /**
     * 按照业务id查询流程实例id
     *
     * @param businessId 业务id
     * @return 结果
     */
    @Override
    public Long getInstanceIdByBusinessId(String businessId) {
        FlowInstance flowInstance = flwInstanceService.selectInstByBusinessId(businessId);
        return ObjectUtil.isNotNull(flowInstance) ? flowInstance.getId() : null;
    }

    /**
     * 启动流程
     *
     * @param startProcess 参数
     * @return 启动后的流程实例和首个任务信息
     */
    @Override
    public StartProcessReturnDTO startWorkFlow(StartProcessDTO startProcess) {
        return flwTaskService.startWorkFlow(BeanUtil.toBean(startProcess, StartProcessBo.class));
    }

    /**
     * 办理任务
     * 系统后台发起审批 无用户信息 需要忽略权限
     * completeTask.getVariables().put("ignore", true);
     *
     * @param completeTask 参数
     * @return 办理成功返回 {@code true}
     */
    @Override
    public boolean completeTask(CompleteTaskDTO completeTask) {
        return flwTaskService.completeTask(BeanUtil.toBean(completeTask, CompleteTaskBo.class));
    }

    /**
     * 办理任务
     *
     * @param taskId  任务ID
     * @param message 办理意见
     * @return 办理成功返回 {@code true}
     */
    @Override
    public boolean completeTask(Long taskId, String message) {
        CompleteTaskBo completeTask = new CompleteTaskBo();
        completeTask.setTaskId(taskId);
        completeTask.setMessage(message);
        // 忽略权限(系统后台发起审批 无用户信息 需要忽略权限)
        completeTask.getVariables().put("ignore", true);
        return flwTaskService.completeTask(completeTask);
    }

    /**
     * 启动流程并办理第一个任务
     *
     * @param startProcess 参数
     * @return 首节点办理成功返回 {@code true}
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean startCompleteTask(StartProcessDTO startProcess) {
        StartProcessBo processBo = new StartProcessBo();
        processBo.setBusinessId(startProcess.getBusinessId());
        processBo.setFlowCode(startProcess.getFlowCode());
        processBo.setVariables(startProcess.getVariables());
        processBo.setHandler(startProcess.getHandler());
        processBo.setBizExt(BeanUtil.toBean(startProcess.getBizExt(), FlowInstanceBizExt.class));

        StartProcessReturnDTO result = flwTaskService.startWorkFlow(processBo);
        CompleteTaskBo taskBo = new CompleteTaskBo();
        taskBo.setTaskId(result.taskId());
        taskBo.setMessageType(Collections.singletonList(MessageTypeEnum.SYSTEM_MESSAGE.getCode()));
        taskBo.setVariables(startProcess.getVariables());
        taskBo.setHandler(startProcess.getHandler());
        return flwTaskService.completeTask(taskBo);
    }

    /**
     * 按业务 id 查询当前待办任务（中间节点）
     *
     * @param businessId 业务id
     * @return 当前待办任务，无则返回 {@code null}
     */
    private FlowTask currentTask(String businessId) {
        FlowInstance flowInstance = flwInstanceService.selectInstByBusinessId(businessId);
        if (ObjectUtil.isNull(flowInstance)) {
            return null;
        }
        List<FlowTask> tasks = flwTaskService.selectByInstId(flowInstance.getId());
        return tasks.stream()
            .filter(t -> Integer.valueOf(1).equals(t.getNodeType()))
            .findFirst()
            .orElse(null);
    }

    @Override
    public Long getCurrentTaskId(String businessId) {
        FlowTask task = currentTask(businessId);
        return ObjectUtil.isNotNull(task) ? task.getId() : null;
    }

    @Override
    public String getCurrentNodeCode(String businessId) {
        FlowTask task = currentTask(businessId);
        return ObjectUtil.isNotNull(task) ? task.getNodeCode() : null;
    }

    /**
     * 驳回当前待办任务（系统身份忽略权限，驳回到流程申请人节点）。
     */
    @Override
    public boolean rejectTask(Long taskId, String message) {
        List<FlowTask> tasks = flwTaskService.selectByIdList(Collections.singletonList(taskId));
        if (tasks == null || tasks.isEmpty()) {
            throw new IllegalStateException("待办任务不存在：taskId=" + taskId);
        }
        FlowTask task = tasks.get(0);
        String applyNodeCode = flwCommonService.applyNodeCode(task.getDefinitionId());

        BackProcessBo bo = new BackProcessBo();
        bo.setTaskId(taskId);
        bo.setNodeCode(applyNodeCode);
        bo.setMessage(message);
        bo.setMessageType(Collections.singletonList(MessageTypeEnum.SYSTEM_MESSAGE.getCode()));
        bo.getVariables().put("ignore", true);
        return flwTaskService.backProcess(bo);
    }

    /**
     * 超时自动通过：扫描指定节点集合上的待办中间任务，创建时间超过 timeoutHours 的系统自动办理。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int autoCompleteTimeoutTasks(Set<String> nodeCodes, int timeoutHours, String message) {
        if (nodeCodes == null || nodeCodes.isEmpty() || timeoutHours <= 0) {
            return 0;
        }
        Date deadline = new Date(System.currentTimeMillis() - timeoutHours * 3600_000L);
        List<Task> allTasks = FlowEngine.taskService().list(new FlowTask());
        int done = 0;
        for (Task task : allTasks) {
            if (!Integer.valueOf(1).equals(task.getNodeType())
                || task.getNodeCode() == null || !nodeCodes.contains(task.getNodeCode())
                || task.getCreateTime() == null || task.getCreateTime().after(deadline)) {
                continue;
            }
            CompleteTaskBo taskBo = new CompleteTaskBo();
            taskBo.setTaskId(task.getId());
            taskBo.setMessage(message);
            taskBo.setMessageType(Collections.singletonList(MessageTypeEnum.SYSTEM_MESSAGE.getCode()));
            taskBo.getVariables().put("ignore", true);
            try {
                flwTaskService.completeTask(taskBo);
                done++;
                log.info("[工作流-超时自动审批] taskId={}, nodeCode={}, instanceId={}",
                    task.getId(), task.getNodeCode(), task.getInstanceId());
            } catch (Exception e) {
                // 单条失败不阻断其余任务（如流程状态已被人工抢先办理）
                log.warn("[工作流-超时自动审批] 自动办理失败：taskId={}, nodeCode={}, reason={}",
                    task.getId(), task.getNodeCode(), e.getMessage());
            }
        }
        return done;
    }
}
