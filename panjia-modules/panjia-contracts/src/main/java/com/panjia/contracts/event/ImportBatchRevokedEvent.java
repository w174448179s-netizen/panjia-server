package com.panjia.contracts.event;

import lombok.Data;

import java.util.List;

/**
 * 导入批次撤销事件（跨域契约事件，panjia-contracts 叶子模块）。
 * <p>
 * 触发时机：导入批次被硬删除时由 panjia-import 域发布。
 * <p>
 * 下游消费：
 * <ul>
 *   <li>业绩域：硬删该批次的所有事实、实收审批单、消费日志及关联的流程实例；
 *       若本批次曾冲销旧批次（restoredBatchIds 非空），同步恢复旧批次事实为 ACTIVE，
 *       并重放实收通过事件重建结佣草稿；无旧批次可恢复时，把因本批新签自动通过的
 *       实收单回退为 DRAFT；</li>
 *   <li>实收域：硬删该批次实收合同/明细/审批单；restoredBatchIds 非空时恢复旧批次
 *       实收明细为 ACTIVE，重算关联审批单金额并重放实收通过事件；</li>
 *   <li>结佣域：删除该批次关联合同下自动产生的结佣草稿单（DRAFT 无流程实例），
 *       HISTORY_PAYROLL 维持期间 LOCKED 无流程单的清理语义。</li>
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

    /**
     * 被本批次冲销、随本次撤销恢复生效的旧批次 ID 列表（字符串承载，无旧批次时为空列表）。
     * <p>旧批次行在导入域事务内已恢复为 ARCHIVED（superseded_by_batch_id 置空），
     * 下游据此把旧批次事实/实收明细从 REVERSED/SUPERSEDED 翻回 ACTIVE。
     */
    private List<String> restoredBatchIds;

    /**
     * 本批次及被恢复旧批次涉及的合同号集合（去重，去空白；仅 KE_SIGNED/KE_RECEIVED 填充）。
     * <p>结佣域据此删除自动产生的 DRAFT 结佣草稿单；事实/明细在下游各域自清理，
     * 不依赖此集合，它只服务于跨域拿不到批次数据的结佣域。
     */
    private List<String> contractNos;

    @Override
    public String eventType() {
        return EVENT_TYPE;
    }

    @Override
    public long businessId() {
        return batchId;
    }
}
