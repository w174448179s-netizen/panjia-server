package com.panjia.commission.domain.bo;

import lombok.Data;

import java.util.List;

/**
 * 按合同号批量审批请求体。
 */
@Data
public class CommissionBatchApproveBo {
    private String period;
    private List<String> contractNos;
}
