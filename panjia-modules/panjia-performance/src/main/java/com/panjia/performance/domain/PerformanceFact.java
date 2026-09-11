package com.panjia.performance.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 业绩事实（对应 pj_perf_fact 表）。
 * <p>
 * 一条业绩事实记录代表某员工在某期间内、某口径下的一笔业绩明细。
 * 事实状态由 {@link FactStatus} 管理，支持冲销（ACTIVE → REVERSED）。
 * <p>
 * 不继承 RuoYi BaseEntity：本表无 create_by/update_by 审计列，
 * create_time/update_time 由数据库默认值填充。
 */
@Data
@TableName("pj_perf_fact")
public class PerformanceFact implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 事实 ID（雪花 ID） */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 事实口径：PERF_REAL-结佣业绩(实收) / PERF_EXPECT-新签业绩(应收) */
    private FactType factType;

    /** 归属期间 YYYY-MM */
    private String period;

    /** 业务发生日 */
    private LocalDate businessDate;

    /** 来源导入批次 ID */
    private Long batchId;

    /** 归一化记录 ID */
    private Long normalizedRecordId;

    /** 来源业务单号（幂等锚点） */
    private String sourceKey;

    /** 业务类型 */
    private String bizType;

    /** 员工 ID */
    private Long employeeId;

    /** 员工外部编码（工号） */
    private String employeeExternalCode;

    /** 归属部门 ID */
    private Long deptId;

    /** 角色类型 */
    private String roleType;

    /** 分摊比例 */
    private BigDecimal shareRatio;

    /** 原始金额 */
    private BigDecimal originAmount;

    /** 折算系数 */
    private BigDecimal conversionRate;

    /** 业绩金额 */
    private BigDecimal performanceAmount;

    /** 生效起始日 */
    private LocalDate effectiveDate;

    /** 生效截止日 */
    private LocalDate expireDate;

    /** 事实状态：ACTIVE-有效 / REVERSED-已冲销 */
    private FactStatus factStatus;

    /** 来源：IMPORT-导入生成 / MANUAL-手工录入 */
    private PerformanceSource source;

    /** 关联调整单 ID */
    private Long adjustId;

    /** 冲销原因 */
    private ReversedReason reversedReason;

    /** 操作人 ID */
    private Long operatorId;

    /** 乐观锁版本号 */
    @Version
    private Integer version;

    /** 创建时间（DB 默认填充） */
    private LocalDateTime createTime;

    /** 更新时间（DB 默认填充） */
    private LocalDateTime updateTime;
}
