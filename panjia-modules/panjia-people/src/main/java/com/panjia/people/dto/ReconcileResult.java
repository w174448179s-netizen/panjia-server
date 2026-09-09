package com.panjia.people.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 员工-账户对账结果。
 * <p>
 * 规则：people 单向覆盖 sys_user（dept_id / sys_user_post / sys_user_role / 离职禁用），
 * 不碰 sys_role_dept。
 */
@Data
public class ReconcileResult {

    /** 参与对账的员工总数 */
    private int totalEmployees;

    /** 修复的差异总数 */
    private int fixedCount;

    /** 差异明细（已修复） */
    private List<DiffItem> items = new ArrayList<>();

    /**
     * 追加一条差异并计数。
     *
     * @param item 差异项
     */
    public void add(DiffItem item) {
        items.add(item);
        fixedCount++;
    }

    /**
     * 单条对账差异。
     */
    @Data
    public static class DiffItem {

        /** 员工 ID */
        private Long employeeId;

        /** 工号 */
        private String employeeCode;

        /** 姓名 */
        private String employeeName;

        /** 差异字段（dept/posts/status） */
        private String field;

        /** 修复前 */
        private String beforeValue;

        /** 修复后 */
        private String afterValue;

        /**
         * 构造差异项。
         *
         * @param employeeId    员工 ID
         * @param employeeCode  工号
         * @param employeeName  姓名
         * @param field         差异字段
         * @param beforeValue   修复前
         * @param afterValue    修复后
         */
        public DiffItem(Long employeeId, String employeeCode, String employeeName,
                        String field, String beforeValue, String afterValue) {
            this.employeeId = employeeId;
            this.employeeCode = employeeCode;
            this.employeeName = employeeName;
            this.field = field;
            this.beforeValue = beforeValue;
            this.afterValue = afterValue;
        }
    }
}
