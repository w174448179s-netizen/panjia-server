package com.panjia.performance.service;

import com.panjia.contracts.port.ApprovalAction;
import com.panjia.performance.domain.ReceivedApply;
import com.panjia.performance.dto.ReceivedApplyQuery;
import com.panjia.performance.dto.ReceivedBatchApproveResult;
import org.dromara.common.core.domain.PageResult;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 实收业绩审批单服务（§2 实收业绩流程）。
 */
public interface ReceivedApplyService {

    /**
     * 导入批次归档后自动建单并提交（§2.1：有实收 → 自动提交，流转财务→总监）。
     * <p>幂等：仅处理批次内 received_apply_id 尚未绑定的实收事实；同合同已有审批单时合并。
     *
     * @param batchId    导入批次 ID
     * @param period     归属期间
     * @param operatorId 操作人 ID（归档操作发起人，用于设置审批单创建人；为空时兜底取登录用户）
     * @return 新建审批单数量（合并不计）
     */
    int autoCreateForBatch(Long batchId, String period, Long operatorId);

    /**
     * 手工提交（店长/财务/总监，§2.2 发起人路由）：无单则按合同实收事实自动建单并提交；
     * 财务发起直达总监；总监发起直接通过；店长发起走 财务→总监。
     *
     * @param period     结算月
     * @param contractNo 合同号
     * @return 审批单
     */
    ReceivedApply manualSubmit(String period, String contractNo);

    /**
     * 已驳回/草稿单重新提交。
     *
     * @param id 审批单 ID
     * @return 审批单
     */
    ReceivedApply resubmit(Long id);

    /**
     * 业务明细直接审批（双入口 §三）：支持 PASS 通过 / REJECT 驳回。
     * <p>与「我的待办」共用同一底层 completeTask，留痕一致；服务端鉴权由流程引擎原生权限校验保障。
     *
     * @param id      审批单 ID
     * @param action  审批动作（PASS / REJECT）
     * @param comment 审批意见（可选，留空时按动作给默认值）
     */
    void approve(Long id, ApprovalAction action, String comment);

    /**
     * 便捷方法：默认 PASS 通过。
     *
     * @param id      审批单 ID
     * @param message 审批意见
     */
    void approve(Long id, String message);

    /**
     * 作废审批单（DRAFT/SUBMITTED；SUBMITTED 同步终止流程）。
     *
     * @param id 审批单 ID
     */
    void cancel(Long id);

    /**
     * Excel 批量审批（§2.3：匹配 合同号 + 实收金额，逐单办理当前待办节点）。
     *
     * @param period 结算月（必填，防止跨月误批）
     * @param file   Excel 文件（.xlsx/.xls，含「合同号」「实收金额」列）
     * @return 成功/失败明细
     */
    ReceivedBatchApproveResult batchApprove(String period, MultipartFile file);

    /**
     * 工作流回调（ReceivedWorkflowListener 调用）。
     */
    void handleWorkflowEvent(Long applyId, String status, String handler, String message);

    /** 分页查询 */
    PageResult<ReceivedApply> list(ReceivedApplyQuery query, PageQuery pageQuery);

    /** 详情（含合同下每人实收明细，列口径对齐合同业绩明细） */
    ReceivedApplyDetail getDetail(Long id);

    /** 实收审批单明细视图 */
    record ReceivedApplyDetail(ReceivedApply apply,
                               List<com.panjia.performance.dto.ReceivedFactDetailDTO> facts) {
    }
}
