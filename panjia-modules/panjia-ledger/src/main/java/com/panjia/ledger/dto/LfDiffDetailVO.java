package com.panjia.ledger.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 合同差异明细：流水侧与实收侧。
 */
@Data
public class LfDiffDetailVO {

    private String contractNo;
    private BigDecimal flowAmount;
    private BigDecimal receivedAmount;
    private BigDecimal diffAmount;
    private String matchStatus;

    private List<FlowLine> flowLines = new ArrayList<>();
    private List<ReceivedLine> receivedLines = new ArrayList<>();

    @Data
    public static class FlowLine {
        private Long id;
        private String period;
        private String storeName;
        private LocalDateTime transactionTime;
        private String incomeExpenseType;
        private String businessDesc;
        private String transactionRemark;
        private BigDecimal transactionAmount;
        private String voucherNo;
    }

    @Data
    public static class ReceivedLine {
        private Long id;
        private String period;
        private Long employeeId;
        private String roleName;
        private String roleType;
        private BigDecimal performanceAmount;
        private String sourceType;
        private String applyStatus;
    }
}
