package com.panjia.performance.domain.bo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 实收调整单分页查询条件。
 * <p>数据权限同新签调整：登录用户仅本部门（含下级），deptId 由后端强制收敛。
 */
@Data
public class ReceivedAdjustBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 归属期间 */
    private String period;

    /** 单据状态 */
    private String status;

    /** 员工 ID */
    private Long employeeId;

    /** 部门 ID（含下级子树） */
    private Long deptId;

    /** 业务类型（一手房/房产金融等） */
    private String bizType;

    /** 调整类型（AMOUNT/DEDUCT/INCREASE；可空） */
    private String adjustType;

    /** 关键字（调整单号/合同号/订单号模糊） */
    private String keyword;
}
