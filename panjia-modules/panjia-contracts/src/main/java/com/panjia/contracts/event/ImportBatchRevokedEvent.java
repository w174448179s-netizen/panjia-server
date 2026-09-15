package com.panjia.contracts.event;

import lombok.Data;

/**
 * 导入批次撤销事件（跨域契约事件，panjia-contracts 叶子模块）。
 * <p>
 * 触发时机：导入批次被硬删除时由 panjia-import 域发布。
 * <p>
 * 下游消费：
 * <ul>
 *   <li>业绩域：硬删该批次的所有事实、实收审批单、消费日志及关联的流程实例</li>
 *   <li>结佣域：取消该批次关联的未生效结佣申请</li>
 * </ul>
 * <p>
 * 依赖方向：定义在 panjia-contracts 叶子模块，业务域单向依赖该事件，
 * panjia-import 只依赖 contracts 而非下游域。
 */
@Data
public class ImportBatchRevokedEvent implements DomainEvent {

    /** 事件类型常量（路由与序列化） */
    public static final String EVENT_TYPE = "import.batch.revoked";

    /** 批次 ID（业务主键） */
    private long batchId;

    /** 来源类型（ImportSourceType code，如 KE_SIGNED / ATTENDANCE） */
    private String sourceType;

    /** 归属期间（YYYY-MM） */
    private String period;

    /** 操作人 ID（撤销操作发起人） */
    private Long operatorId;

    @Override
    public String eventType() {
        return EVENT_TYPE;
    }

    @Override
    public long businessId() {
        return batchId;
    }
}
