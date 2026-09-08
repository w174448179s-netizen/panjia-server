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
        "employeeCode",       // 工号
        "agentName",          // 经纪人姓名
        "performanceAmount",  // 业绩金额
        "signDate",           // 签约日期
        "bizType",            // 业务类型
        "attendanceDays",     // 出勤天数
        "lateCount",          // 迟到次数
        "absentDays",         // 旷工天数
        "scoreValue",         // 积分值
        "grade",              // 等级（A/B/C）
        "sourceType",         // 来源类型（SHELL/ATTENDANCE/SCORE/MANUAL/COST）
        "importBatchId"       // 导入批次 ID
    );

    private NormalizedRecordFields() {
        // 常量类，禁止实例化
    }
}
