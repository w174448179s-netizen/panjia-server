package com.panjia.contracts.constant;

import java.util.Set;

/**
 * NormalizedRecord 字段白名单。
 * <p>
 * 本清单是 Task-0-1 check-import-template-static 校验 target_field
 * 与 ArchUnit 校验事件 payload 的共同来源。
 * 新增字段须先改此处（动态扩展见 Backlog IMP-002）。
 * <p>
 * V1 固定集合，禁止运行时修改。
 */
public final class NormalizedRecordFields {

    /**
     * 允许的 target_field 集合（V1 固定）。
     * 对应 NormalizedRecord 归一化后的属性名。
     */
    public static final Set<String> WHITELIST = Set.of(
        // ===== V1.4 归一化记录字段（业绩类专用） =====
        "period",                // 归属月 YYYY-MM
        "employeeId",            // 关联 Employee.id
        "employeeExternalCode",  // 外部编码（系统号/工号）
        "sourceKey",             // 业务唯一键（订单号/合同号/考勤日期）
        "bizType",               // 业务类型
        "receivableAmount",      // 应收业绩（原值）
        "receivedAmount",        // 实收业绩（原值）
        "shareRatio",            // 业绩比例
        "roleType",              // 角色类型
        "rawDataId",             // 对应 RawData 行 ID
        // ===== 历史字段（V100003 种子兼容，V1.4 已不用，保留至种子迁移完成） =====
        "employeeCode",
        "agentName",
        "performanceAmount",
        "signDate",
        "attendanceDays",
        "lateCount",
        "absentDays",
        "scoreValue",
        "grade",
        "sourceType",
        "importBatchId"
    );

    private NormalizedRecordFields() {
        // 常量类，禁止实例化
    }
}
