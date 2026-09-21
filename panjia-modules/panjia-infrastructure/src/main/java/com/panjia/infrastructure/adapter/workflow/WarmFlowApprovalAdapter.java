package com.panjia.infrastructure.adapter.workflow;

import com.panjia.contracts.port.ApprovalAction;
import com.panjia.contracts.port.ApprovalPort;
import com.panjia.contracts.port.ApprovalStartCmd;
import com.panjia.contracts.port.MyTaskBrief;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.workflow.api.WorkflowService;
import org.dromara.workflow.api.domain.CompleteTaskDTO;
import org.dromara.workflow.api.domain.FlowInstanceBizExtDTO;
import org.dromara.workflow.api.domain.StartProcessDTO;
import org.dromara.workflow.api.domain.StartProcessReturnDTO;
import org.dromara.workflow.domain.bo.BackProcessBo;
import org.dromara.workflow.mapper.FlwInstanceMapper;
import org.dromara.workflow.mapper.FlwUserMapper;
import org.dromara.workflow.service.IFlwInstanceService;
import org.dromara.workflow.service.IFlwTaskService;
import org.dromara.warm.flow.orm.entity.FlowInstance;
import org.dromara.warm.flow.orm.entity.FlowTask;
import org.dromara.warm.flow.orm.entity.FlowUser;
import org.springframework.stereotype.Component;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Warm-Flow 审批适配器：实现 {@link ApprovalPort}，桥接业务域与 RuoYi-Plus 工作流引擎。
 * <p>
 * 本类是业务域唯一允许感知 {@code org.dromara.workflow} 的位置（依赖隔离铁律的唯一例外）。
 * 换引擎时只替换本类，业务代码零改动。
 * <p>
 * 设计依据：《审批集成设计说明 V1.0》§五。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WarmFlowApprovalAdapter implements ApprovalPort {

    private final WorkflowService workflowService;
    private final IFlwTaskService flwTaskService;
    private final IFlwInstanceService flwInstanceService;
    private final FlwUserMapper flwUserMapper;
    private final FlwInstanceMapper flwInstanceMapper;

    @Override
    public Long start(String bizType, Long bizId, ApprovalStartCmd cmd) {
        StartProcessDTO dto = toStartProcess(bizType, bizId, cmd);
        StartProcessReturnDTO ret = workflowService.startWorkFlow(dto);
        Long instanceId = ret == null ? null : ret.processInstanceId();
        log.info("[审批适配器] 启动流程：bizType={}, bizId={}, instanceId={}", bizType, bizId, instanceId);
        return instanceId;
    }

    @Override
    public boolean startAndCompleteFirst(String bizType, Long bizId, ApprovalStartCmd cmd) {
        StartProcessDTO dto = toStartProcess(bizType, bizId, cmd);
        return workflowService.startCompleteTask(dto);
    }

    @Override
    public boolean complete(String bizType, Long bizId, ApprovalAction action, String comment) {
        Long taskId = workflowService.getCurrentTaskId(String.valueOf(bizId));
        if (taskId == null) {
            throw new ServiceException("当前无待办任务，无法办理：bizType=" + bizType + ", bizId=" + bizId);
        }
        if (action == ApprovalAction.REJECT) {
            return reject(taskId, comment);
        }
        // PASS：登录用户鉴权办理（CompleteTaskDTO，不设 ignore）
        CompleteTaskDTO dto = new CompleteTaskDTO();
        dto.setTaskId(taskId);
        dto.setMessage(comment);
        return workflowService.completeTask(dto);
    }

    @Override
    public boolean completeAsSys(String bizType, Long bizId, ApprovalAction action, String comment) {
        Long taskId = workflowService.getCurrentTaskId(String.valueOf(bizId));
        if (taskId == null) {
            throw new ServiceException("当前无待办任务，无法办理：bizType=" + bizType + ", bizId=" + bizId);
        }
        if (action == ApprovalAction.REJECT) {
            return reject(taskId, comment);
        }
        // PASS：系统身份办理（两参重载内部 ignore=true，跳过办理人权限校验）
        return workflowService.completeTask(taskId, comment);
    }

    @Override
    public void cancel(String bizType, Long bizId) {
        workflowService.deleteInstanceSys(List.of(String.valueOf(bizId)));
        log.info("[审批适配器] 撤销流程实例：bizType={}, bizId={}", bizType, bizId);
    }

    @Override
    public void cancelBatch(List<Long> bizIds) {
        if (bizIds == null || bizIds.isEmpty()) {
            return;
        }
        List<String> businessIds = bizIds.stream()
            .map(String::valueOf)
            .collect(Collectors.toList());
        workflowService.deleteInstanceSys(businessIds);
        log.info("[审批适配器] 批量撤销流程实例：count={}", businessIds.size());
    }

    @Override
    public Long currentTaskId(String bizType, Long bizId) {
        return workflowService.getCurrentTaskId(String.valueOf(bizId));
    }

    @Override
    public boolean isMyTask(String bizType, Long bizId) {
        FlowInstance instance = flwInstanceService.selectInstByBusinessId(String.valueOf(bizId));
        if (instance == null) {
            return false;
        }
        List<FlowTask> tasks = flwTaskService.selectByInstId(instance.getId());
        FlowTask task = tasks.stream()
            .filter(t -> Integer.valueOf(1).equals(t.getNodeType()))
            .findFirst()
            .orElse(null);
        if (task == null) {
            return false;
        }
        String userId = LoginHelper.getUserIdStr();
        LambdaQueryWrapper<FlowUser> qw = Wrappers.lambdaQuery(FlowUser.class)
            .eq(FlowUser::getAssociated, task.getId())
            .eq(FlowUser::getProcessedBy, userId);
        Long count = flwUserMapper.selectCount(qw);
        return count != null && count > 0;
    }

    @Override
    public Map<Long, MyTaskBrief> myCurrentTasks(String bizType, Collection<Long> bizIds) {
        if (bizIds == null || bizIds.isEmpty()) {
            return Map.of();
        }
        // ① 一次 IN 查流程实例（business_id 为字符串雪花 ID）
        List<String> bizIdStrs = bizIds.stream().map(String::valueOf).toList();
        List<FlowInstance> instances = flwInstanceMapper.selectList(
            Wrappers.lambdaQuery(FlowInstance.class)
                .in(FlowInstance::getBusinessId, bizIdStrs));
        if (instances.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> bizIdByInstanceId = new HashMap<>(instances.size() * 2);
        List<Long> instanceIds = new ArrayList<>(instances.size());
        for (FlowInstance inst : instances) {
            instanceIds.add(inst.getId());
            bizIdByInstanceId.put(inst.getId(), inst.getBusinessId());
        }
        // ② 一次 IN 查这些实例的全部任务，内存中只取当前待办（nodeType=1），同实例取第一条
        List<FlowTask> tasks = flwTaskService.selectByInstIds(instanceIds);
        Map<Long, FlowTask> currentTaskByInst = new HashMap<>(instanceIds.size() * 2);
        for (FlowTask task : tasks) {
            if (Integer.valueOf(1).equals(task.getNodeType())) {
                currentTaskByInst.putIfAbsent(task.getInstanceId(), task);
            }
        }
        if (currentTaskByInst.isEmpty()) {
            return Map.of();
        }
        // ③ 一次 IN 查当前用户在这些待办任务上的授权（flow_user.processedBy）
        String userId = LoginHelper.getUserIdStr();
        List<Long> taskIds = currentTaskByInst.values().stream().map(FlowTask::getId).toList();
        List<FlowUser> grants = flwUserMapper.selectList(
            Wrappers.lambdaQuery(FlowUser.class)
                .in(FlowUser::getAssociated, taskIds)
                .eq(FlowUser::getProcessedBy, userId));
        Set<Long> grantedTaskIds = grants.stream()
            .map(FlowUser::getAssociated)
            .collect(Collectors.toSet());
        // ④ 反查组装：仅返回被授权单据
        Map<Long, MyTaskBrief> result = new HashMap<>(grantedTaskIds.size() * 2);
        currentTaskByInst.forEach((instanceId, task) -> {
            if (!grantedTaskIds.contains(task.getId())) {
                return;
            }
            Long bizId = parseBizId(bizIdByInstanceId.get(instanceId));
            if (bizId != null) {
                result.put(bizId, new MyTaskBrief(bizId, task.getId(), task.getNodeCode()));
            }
        });
        return result;
    }

    @Override
    public boolean completeTaskAsSys(Long taskId, String comment) {
        if (taskId == null) {
            throw new ServiceException("待办任务 ID 不能为空，无法办理");
        }
        // 两参重载内部 ignore=true，跳过办理人权限校验，与 completeAsSys 系统身份口径一致
        return workflowService.completeTask(taskId, comment);
    }

    /** flow_instance.business_id 字符串 → Long（业务单据 ID 均为雪花数字 ID；非数字返回 null）。 */
    private Long parseBizId(String businessId) {
        if (StringUtils.isBlank(businessId)) {
            return null;
        }
        try {
            return Long.valueOf(businessId);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @Override
    public String currentNodeCode(String bizType, Long bizId) {
        return workflowService.getCurrentNodeCode(String.valueOf(bizId));
    }

    @Override
    public Long instanceId(String bizType, Long bizId) {
        return workflowService.getInstanceIdByBusinessId(String.valueOf(bizId));
    }

    @Override
    public String businessStatus(String bizType, Long bizId) {
        return workflowService.getBusinessStatus(String.valueOf(bizId));
    }

    @Override
    public void setVariable(String bizType, Long bizId, Map<String, Object> variables) {
        Long instanceId = workflowService.getInstanceIdByBusinessId(String.valueOf(bizId));
        if (instanceId == null) {
            throw new ServiceException("流程实例不存在，无法设置流程变量：bizType=" + bizType + ", bizId=" + bizId);
        }
        workflowService.setVariable(instanceId, variables);
        log.info("[审批适配器] 设置流程变量：bizType={}, bizId={}, instanceId={}, keys={}",
            bizType, bizId, instanceId, variables == null ? null : variables.keySet());
    }

    // ==================== 内部 ====================

    private StartProcessDTO toStartProcess(String bizType, Long bizId, ApprovalStartCmd cmd) {
        StartProcessDTO dto = new StartProcessDTO();
        dto.setBusinessId(String.valueOf(bizId));
        dto.setFlowCode(FlowCodeMapping.toFlowCode(bizType));
        if (cmd != null) {
            dto.setHandler(cmd.getHandler());
            Map<String, Object> vars = cmd.getVariables();
            if (vars != null) {
                dto.setVariables(vars);
            }
            dto.setBizExt(toBizExt(bizId, cmd));
        }
        return dto;
    }

    private FlowInstanceBizExtDTO toBizExt(Long bizId, ApprovalStartCmd cmd) {
        FlowInstanceBizExtDTO bizExt = new FlowInstanceBizExtDTO();
        bizExt.setBusinessId(String.valueOf(bizId));
        bizExt.setBusinessCode(cmd.getBusinessCode());
        bizExt.setBusinessTitle(cmd.getBusinessTitle());
        return bizExt;
    }

    private boolean reject(Long taskId, String comment) {
        // 驳回委托 backProcess（SkipType.REJECT），驳回到申请人节点
        BackProcessBo bo = new BackProcessBo();
        bo.setTaskId(taskId);
        bo.setMessage(comment == null ? "驳回" : comment);
        return flwTaskService.backProcess(bo);
    }
}
