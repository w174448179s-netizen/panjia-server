package com.panjia.commission.dto;

import com.panjia.commission.domain.CommissionItem;
import com.panjia.contracts.dto.PerformanceFactSummaryDTO;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 工资-结佣溯源 VO（GET /commission/trace/{itemId}）。
 * <p>
 * 溯源链路（C-15）：结佣明细 → 业绩事实 → 贝壳原始行（raw_json 全量 30 列）。
 * DIFF 差额行不挂业绩事实，fact / rawJson 为 null。
 */
@Data
public class ItemTraceVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 结佣明细 */
    private CommissionItem item;

    /** 关联业绩事实摘要（DIFF 行为 null） */
    private PerformanceFactSummaryDTO fact;

    /** 贝壳原始行全量 JSON（经归一化记录回溯原始行表；无关联时为 null） */
    private String rawJson;
}
