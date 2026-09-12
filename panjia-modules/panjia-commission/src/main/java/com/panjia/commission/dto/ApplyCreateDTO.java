package com.panjia.commission.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 发起结佣请求 DTO（门店 + 结算月）。
 */
@Data
public class ApplyCreateDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 业绩归属月（结算月 YYYY-MM） */
    @NotBlank(message = "结算月不能为空")
    private String period;

    /** 门店 ID */
    @NotNull(message = "门店不能为空")
    private Long deptId;
}
