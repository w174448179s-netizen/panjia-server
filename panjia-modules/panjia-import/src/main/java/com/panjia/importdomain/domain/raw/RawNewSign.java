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
 * 贝壳新签原始归档（KE_NEW_SIGN）。insert-only。
 */
@Data
@TableName("pj_import_raw_new_sign")
public class RawNewSign implements RawData {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long batchId;
    private Integer rowNo;
    private String rawJson;
    private LocalDateTime createTime;

    private String arriveMonth;
    private String bizType;
    private String orderNo;
    private String contractNo;
    private String roleSysNo;
    private String roleName;
    private String roleType;
    private BigDecimal shareRatio;
    private BigDecimal currentReceivable;
    private BigDecimal currentReceived;
}
