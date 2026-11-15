package com.panjia.people.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 人工绑定请求：把渠道账号绑定到指定员工。
 */
@Data
public class ImUserMappingBindDTO {

    /** 本地员工 ID */
    @NotNull(message = "请选择要绑定的员工")
    private Long employeeId;
}
