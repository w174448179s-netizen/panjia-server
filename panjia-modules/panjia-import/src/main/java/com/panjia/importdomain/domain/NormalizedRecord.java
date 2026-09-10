package com.panjia.importdomain.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 归一化记录（业绩类专用，V1.4 §3.3）。
 * <p>
 * EMPLOYEE 不产 NormalizedRecord，走 EmployeeImportSink。
 * 不含业务语义字段（提成/折算），只有结构化数据 + 关联键 + 金额原值。
 */
@Data
@TableName("pj_normalized_record")
public class NormalizedRecord implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long batchId;
    private NormalizedRecordType recordType;
    private String period;

    /** 关联 Employee.id，匹配失败为 null */
    private Long employeeId;

    /** 外部编码（系统号/工号），匹配键 */
    private String employeeExternalCode;

    /** 业务唯一键（订单号/合同号/考勤日期），下游去重依据 */
    private String sourceKey;

    private String bizType;
    private BigDecimal receivableAmount;
    private BigDecimal receivedAmount;
    private BigDecimal shareRatio;
    private String roleType;

    /** 扩展字段（扣款金额、考勤细分等） */
    private String extraJson;

    /** 对应 RawData 行 ID（手工录入为 null） */
    private Long rawDataId;

    /** 0 PENDING 1 PASSED 2 FAILED */
    private Integer validationStatus;
    private String validationMsg;

    private LocalDateTime createTime;
}
