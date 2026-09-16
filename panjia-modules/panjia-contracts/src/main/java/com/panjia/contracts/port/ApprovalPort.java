package com.panjia.contracts.port;

import com.panjia.contracts.constant.BizType;

import java.util.List;
import java.util.Map;

/**
 * 审批端口：业务域经此依赖工作流能力，禁止直接依赖 ruoyi-workflow。
 * <p>
 * 适配器由 infrastructure 层实现，桥接 Warm-Flow。换引擎时只替换适配器，业务代码零改动。
 * <p>
 * 设计依据：《审批集成设计说明 V1.0》§五。
 *
 * @see BizType
 */
public interface ApprovalPort {

    /**
     * 启动流程（仅启动，不办理首节点）。
     */
    Long start(String bizType, Long bizId, ApprovalStartCmd cmd);

    /**
     * 启动流程并办理首个节点（申请人提交即办理，对应 {@code startCompleteTask}）。
     *
     * @return 首节点办理是否成功
     */
    boolean startAndCompleteFirst(String bizType, Long bizId, ApprovalStartCmd cmd);

    /**
     * 以当前登录人身份办理当前节点（走引擎 flow_user 鉴权；设计文档 §3.3 底线 2：前端隐藏 ≠ 安全）。
     * <p>越权时引擎抛异常，由调用方转译为业务提示。
     */
    boolean complete(String bizType, Long bizId, ApprovalAction action, String comment);

    /**
     * 系统身份办理当前节点（ignore=true，跳过办理人权限校验）。
     * <p>用于重新提交、系统自动审批、超管运维等无登录办理人或需跳过鉴权的场景。
     */
    boolean completeAsSys(String bizType, Long bizId, ApprovalAction action, String comment);

    /**
     * 撤销流程实例（系统级、无用户上下文，硬删实例/任务/历史）。
     * <p>必须走完整 LiteFlow 删除链路（加载→校验→发事件→执行删除），禁止手写 SQL 删 flow_* 表。
     */
    void cancel(String bizType, Long bizId);

    /**
     * 批量撤销流程实例（按 businessId，系统级）。用于导入撤销等级联删除场景。
     */
    void cancelBatch(List<Long> bizIds);

    /**
     * 按业务 ID 查当前待办任务 ID（业务明细入口用，设计文档 §3.2）。
     *
     * @return 当前待办任务 ID，实例已结束或不存在时返回 {@code null}
     */
    Long currentTaskId(String bizType, Long bizId);

    /**
     * 按业务 ID 查当前待办节点编码。
     *
     * @return 当前节点编码（如 rcv_director / capp_director），无待办任务时返回 {@code null}
     */
    String currentNodeCode(String bizType, Long bizId);

    /**
     * 按业务 ID 查流程实例 ID（回写业务单据 processInstanceId 用）。
     */
    Long instanceId(String bizType, Long bizId);

    /**
     * 按业务 ID 查流程业务状态（finish/back/审批中等）。
     */
    String businessStatus(String bizType, Long bizId);

    /**
     * 设置流程变量（用于网关 skip_condition 求值，T-04 条件跳过财务）。
     * <p>
     * 业务域在关键节点办理前调用，写入变量供引擎路由判断。例如结佣流程
     * 在总监办理前写入 realAmount / expectedAmount，互斥网关按
     * {@code eq@@${realAmount}@@${expectedAmount}} 求值决定是否跳过财务节点。
     * <p>
     * 设计依据：《审批集成设计说明 V1.0》§2.3 / §七。
     *
     * @param bizType   业务类型（{@link BizType}）
     * @param bizId     业务单据 ID（businessId）
     * @param variables 流程变量键值对
     */
    void setVariable(String bizType, Long bizId, Map<String, Object> variables);
}
