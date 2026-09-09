package com.panjia.people.service;

import com.panjia.people.dto.ReconcileResult;

/**
 * 员工-系统账户对账服务（V5.2）。
 * <p>
 * 以 people 员工主数据为准，单向修复 sys_user 差异：
 * dept_id、sys_user_post + sys_user_role（按岗位名推导）、离职账户禁用；
 * 不碰 sys_role_dept。
 */
public interface ReconcileService {

    /**
     * 执行对账并自动修复。
     *
     * @return 对账结果（含差异明细）
     */
    ReconcileResult runReconcile();
}
