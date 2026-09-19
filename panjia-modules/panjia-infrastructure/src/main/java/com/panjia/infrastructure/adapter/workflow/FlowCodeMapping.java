package com.panjia.infrastructure.adapter.workflow;

import com.panjia.contracts.constant.BizType;

import java.util.Map;

/**
 * bizType ↔ flowCode 映射表（换引擎时只需改本类）。
 * <p>
 * 设计依据：《审批集成设计说明 V1.0》§五——适配器集中映射，业务域无感。
 */
final class FlowCodeMapping {

    /** bizType → flowCode */
    private static final Map<String, String> BIZ_TO_FLOW = Map.of(
        BizType.REAL_CONFIRM, "perf_received",
        BizType.COMMISSION, "commission_apply",
        BizType.PERF_ADJUST, "perf_adjust",
        BizType.COMMISSION_ADJUST, "commission_adjust",
        BizType.PAYROLL_BATCH, "payroll_batch",
        BizType.ATTENDANCE_APPROVAL, "attendance_approval"
    );

    /** flowCode → bizType（事件转译用逆映射） */
    private static final Map<String, String> FLOW_TO_BIZ = Map.of(
        "perf_received", BizType.REAL_CONFIRM,
        "commission_apply", BizType.COMMISSION,
        "perf_adjust", BizType.PERF_ADJUST,
        "commission_adjust", BizType.COMMISSION_ADJUST,
        "payroll_batch", BizType.PAYROLL_BATCH,
        "attendance_approval", BizType.ATTENDANCE_APPROVAL
    );

    private FlowCodeMapping() {
    }

    static String toFlowCode(String bizType) {
        String flowCode = BIZ_TO_FLOW.get(bizType);
        if (flowCode == null) {
            throw new IllegalArgumentException("未知 bizType，无法映射 flowCode: " + bizType);
        }
        return flowCode;
    }

    static String toBizType(String flowCode) {
        return flowCode == null ? null : FLOW_TO_BIZ.get(flowCode);
    }
}
