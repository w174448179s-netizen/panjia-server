package com.panjia.importdomain.domain.raw;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 贝壳实收原始归档（KE_RECEIVED，理房通到账贡献明细表）。insert-only，禁止 UPDATE/DELETE。
 * <p>
 * 核心金额列 {@link #roleArrivalAmount}（角色人当月到账金额）可为负（退单负实收）。
 */
@Data
@TableName("pj_import_raw_received")
public class RawReceived implements RawData {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long batchId;
    private Integer rowNo;
    /** 全量原始行 JSONB */
    private String rawJson;
    private LocalDateTime createTime;

    private String arriveMonth;
    private String bizType;
    private String orderNo;
    private String contractNo;
    private String signDate;
    private String propertyAddress;
    private String roleSysNo;
    private String roleName;
    private String roleType;
    private BigDecimal shareRatio;
    /** 角色人当月到账金额（实收事实金额来源，可为负） */
    private BigDecimal roleArrivalAmount;
    /** 合同当月到账金额（口径参考，不参与比较） */
    private BigDecimal contractArrivalAmount;
    /** 贝壳口径当月应收业绩（参考列） */
    private BigDecimal currentReceivable;
    /** 贝壳口径当月实收业绩（参考列） */
    private BigDecimal currentReceived;
    private String deptCode;
    private String deptName;
    private String storeCode;
    private String storeName;
    private String remark;
}
