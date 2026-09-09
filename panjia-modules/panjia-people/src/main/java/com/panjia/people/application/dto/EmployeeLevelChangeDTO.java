package com.panjia.people.application.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDate;

/**
 * 职级变更 DTO（晋升/降级）。
 */
@Data
public class EmployeeLevelChangeDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 员工 ID */
    @NotNull(message = "员工 ID 不能为空")
    private Long employeeId;

    /** 新职级编码（A0~A5 / S1 / S2 / DIRECTOR） */
    @NotBlank(message = "新职级编码不能为空")
    private String newLevelCode;

    /** 生效日期（必须 >= 入职日且晚于当前职级生效日） */
    @NotNull(message = "生效日期不能为空")
    private LocalDate effectiveDate;

    /** 变更原因 */
    private String reason;
}
