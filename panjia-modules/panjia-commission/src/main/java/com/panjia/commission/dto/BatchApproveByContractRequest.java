package com.panjia.commission.dto;

import lombok.Data;

import java.util.List;

/**
 * 按合同号批量审批请求体。
 */
@Data
public class BatchApproveByContractRequest {
    private String period;
    private List<String> contractNos;
}
