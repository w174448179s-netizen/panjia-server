package com.panjia.contracts.port;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * 当前登录用户可办理的待办任务摘要（批量审批预检结果项）。
 * <p>
 * 由 {@link ApprovalPort#myCurrentTasks} 批量返回，调用方异步办理时
 * 直接用 {@code taskId} 调 {@link ApprovalPort#completeTaskAsSys}，
 * 无需再逐单查流程实例/当前任务/节点。
 *
 * @param bizId    业务单据 ID（flow_instance.business_id）
 * @param taskId   当前待办任务 ID（flow_task.id，nodeType=1）
 * @param nodeCode 当前节点编码（如 capp_director / rcv_finance）
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class MyTaskBrief implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long bizId;

    private Long taskId;

    private String nodeCode;
}
