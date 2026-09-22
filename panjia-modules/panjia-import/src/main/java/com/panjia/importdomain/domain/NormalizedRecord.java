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
 * 归一化记录（五类交易单据归一化产物：业绩/考勤/积分/费用，V2.0）。
 * <p>
 * 不含业务语义字段（提成/折算），只有结构化数据 + 关联键 + 金额原值。
 * 员工主数据不产生归一化记录，员工导入由 people 域内部承接。
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

    /** 订单号（贝壳原始行 order_no，业绩域快照到 pj_perf_fact） */
    private String orderNo;

    /** 合同号（贝壳原始行 contract_no，业绩域快照到 pj_perf_fact） */
    private String contractNo;

    /** 物业地址（raw_json.propertyAddress，业绩域快照到 pj_perf_fact） */
    private String propertyAddress;

    /** 签约(成销)时间原始字符串（raw_json.signDate，业绩层解析为 business_date） */
    private String signDate;

    /** 费用项（raw_json.feeItem，业绩域快照到 pj_perf_fact） */
    private String feeItem;

    private String bizType;
    private BigDecimal receivableAmount;
    private BigDecimal receivedAmount;
    /** 合同累计应收（贝壳「总应收业绩」列），跨月应收只认一次的增量基准 */
    private BigDecimal totalReceivableAmount;
    /** 合同累计实收（贝壳「总实收业绩」列），PERF_REAL 按总实收落库 */
    private BigDecimal totalReceivedAmount;
    private BigDecimal shareRatio;
    private String roleType;

    /** 角色人姓名（raw_json.roleName，业绩域快照到 pj_perf_fact） */
    private String roleName;

    /** 扩展字段（扣款金额、考勤细分等） */
    private String extraJson;

    /** 对应 RawData 行 ID（手工录入为 null） */
    private Long rawDataId;

    /** 0 PENDING 1 PASSED 2 FAILED */
    private Integer validationStatus;
    private String validationMsg;

    private LocalDateTime createTime;
}
