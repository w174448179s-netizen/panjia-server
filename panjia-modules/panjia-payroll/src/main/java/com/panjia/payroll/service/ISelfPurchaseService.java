package com.panjia.payroll.service;

import com.panjia.payroll.domain.SelfPurchase;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 经纪人自购单提成服务（warm-flow：self_purchase_approval）。
 */
public interface ISelfPurchaseService {

    /** 登记自购单（DRAFT） */
    Long create(SelfPurchase item, Long operatorId);

    /** 修改（仅待提交/已驳回） */
    void update(SelfPurchase item, Long operatorId);

    /** 删除（仅待提交/已驳回） */
    void delete(Long id, Long operatorId);

    /** 提交总监审批（发起/重提 warm-flow 流程） */
    void submit(Long id, Long operatorId);

    /** 撤销：SUBMITTED 撤回流程回待提交；APPROVED 作废为 CANCELLED 终态 */
    void cancel(Long id, Long operatorId);

    SelfPurchase getById(Long id);

    List<SelfPurchase> list(String contractNo, Long employeeId, String status);

    /**
     * 算薪取数：已审批通过的自购单 → 员工ID → 合同号 → 加成点数。
     * 仅返回入参员工范围内的记录；无数据返回空 Map。
     */
    Map<Long, Map<String, BigDecimal>> loadApprovedByEmployee(Collection<Long> employeeIds);

    /** 工作流回调（finish/back/cancel 等） */
    void handleWorkflowEvent(Long bizId, String status, String handler, String message);

    /** 认领当前节点待办 */
    boolean claim(Long id);
}
