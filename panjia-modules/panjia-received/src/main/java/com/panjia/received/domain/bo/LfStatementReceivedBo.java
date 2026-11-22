package com.panjia.received.domain.bo;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 理房通流水生成的去化模式实收（跨域入参）。
 * <p>一个合同 + 一个归属月一条，roles 为该合同新签角色人的分摊结果。</p>
 */
@Data
public class LfStatementReceivedBo {

    private Long storeDeptId;
    private String storeName;
    private String contractNo;
    private String orderNo;
    private String period;
    private LocalDateTime businessDate;
    private String propertyAddress;

    /** 新签合同总金额 */
    private BigDecimal contractAmount;

    private List<RoleItem> roles = new ArrayList<>();

    @Data
    public static class RoleItem {
        private Long employeeId;
        private String employeeExternalCode;
        private Long deptId;
        private String roleType;
        private String roleName;
        private BigDecimal shareRatio;
        private BigDecimal amount;
    }
}
