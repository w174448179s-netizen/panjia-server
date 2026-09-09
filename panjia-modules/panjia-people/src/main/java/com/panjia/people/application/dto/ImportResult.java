package com.panjia.people.application.dto;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 员工批量导入结果。
 */
@Data
public class ImportResult implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 成功数 */
    private int successCount;

    /** 失败明细 */
    private List<Failure> failures = new ArrayList<>();

    /**
     * 累加成功数。
     */
    public void incrementSuccess() {
        this.successCount++;
    }

    /**
     * 添加失败明细。
     *
     * @param employeeCode 工号
     * @param errorMessage 错误信息
     */
    public void addFailure(String employeeCode, String errorMessage) {
        this.failures.add(new Failure(employeeCode, errorMessage));
    }

    /**
     * 失败明细。
     */
    @Data
    public static class Failure implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        /** 工号 */
        private String employeeCode;

        /** 错误信息 */
        private String errorMessage;

        public Failure(String employeeCode, String errorMessage) {
            this.employeeCode = employeeCode;
            this.errorMessage = errorMessage;
        }
    }
}
