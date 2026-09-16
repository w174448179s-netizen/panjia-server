package com.panjia.contracts.port;

import lombok.Data;

import java.util.Map;

/**
 * 启动审批流程的命令参数（中立 DTO，业务域构造，适配器转译为引擎原生 StartProcessDTO + bizExt）。
 * <p>
 * 设计依据：《审批集成设计说明 V1.0》§五——业务域不依赖工作流 DTO。
 */
@Data
public class ApprovalStartCmd {

    /** 办理人（可不填，用于覆盖当前节点办理人） */
    private String handler;

    /** 流程变量（如 ignore / initiator / initiatorDeptId 等业务侧设置项） */
    private Map<String, Object> variables;

    /** 业务编码（如单据号），用于流程实例的业务扩展信息展示 */
    private String businessCode;

    /** 业务标题，用于「我的待办」列表展示 */
    private String businessTitle;

    public static ApprovalStartCmd of(String businessCode, String businessTitle) {
        ApprovalStartCmd cmd = new ApprovalStartCmd();
        cmd.businessCode = businessCode;
        cmd.businessTitle = businessTitle;
        return cmd;
    }
}
