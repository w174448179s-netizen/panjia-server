package com.panjia.infrastructure.adapter.workflow;

import com.panjia.contracts.port.ApprovalAction;
import com.panjia.contracts.port.ApprovalPort;
import com.panjia.contracts.port.ApprovalStartCmd;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.workflow.api.WorkflowService;
import org.dromara.workflow.api.domain.CompleteTaskDTO;
import org.dromara.workflow.api.domain.FlowInstanceBizExtDTO;
import org.dromara.workflow.api.domain.StartProcessDTO;
import org.dromara.workflow.api.domain.StartProcessReturnDTO;
import org.dromara.workflow.domain.bo.BackProcessBo;
import org.dromara.workflow.service.IFlwTaskService;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
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
