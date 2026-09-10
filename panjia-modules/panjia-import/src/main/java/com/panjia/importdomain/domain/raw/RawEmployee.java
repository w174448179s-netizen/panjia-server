package com.panjia.importdomain.domain.raw;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 员工主数据原始归档（EMPLOYEE）。insert-only。
 * <p>
 * 业务产物由 people 域 EmployeeImportSink 落地，本表仅作审计锚点。
 */
@Data
@TableName("pj_import_raw_employee")
public class RawEmployee implements RawData {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long batchId;
    private Integer rowNo;
    private String rawJson;
    private LocalDateTime createTime;

    private String employeeCode;
    private String name;
    private String phone;
    private String idCard;
    /** 部门全路径，- 分隔（如 富房-龙湖店-一组） */
    private String deptPath;
    /** 岗位名，/ 分隔（如 经纪人/培训师） */
    private String postNames;
    private String level;
    /** 是/否 */
    private String socialInsured;
    /** 是/否 */
    private String housingInsured;
    private BigDecimal commerceInsurance;
    /** 有/无 */
    private String dormitory;
    /** 是/否 */
    private String partTime;
    /** 师傅工号 */
    private String master;
    private LocalDate entryDate;
}
