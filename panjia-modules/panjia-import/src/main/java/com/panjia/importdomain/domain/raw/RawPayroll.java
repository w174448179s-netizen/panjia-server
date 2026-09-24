package com.panjia.importdomain.domain.raw;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.time.LocalDateTime;

/**
 * 历史工资原始归档（HISTORY_PAYROLL 工资族 sheet 行）。insert-only。
 * <p>
 * 承接 6 个工资族 sheet：工资表(WAGE)/总监工资(DIRECTOR)/店长工资(MANAGER)/
 * 人事数据补丁(HR)/绩效和扣款左半(PERF_LEFT)/绩效和扣款右半(PERF_RIGHT)；
 * 考勤口径行落 {@link RawAttendance}、积分口径行落 {@link RawPoints}、
 * 新签/结佣业绩行落 {@link RawSigned}（recordType 口径标记在 raw_json）。
 */
@Data
@TableName("pj_import_raw_payroll")
public class RawPayroll implements RawData {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long batchId;
    private Integer rowNo;
    /** 全量原始行 JSONB */
    private String rawJson;
    private LocalDateTime createTime;

    /** 工号（历史表无工号列，归一化期按姓名富化后回填） */
    private String employeeCode;
    /** 姓名（历史表按姓名匹配员工主数据） */
    private String employeeName;
    /** 来源 sheet：WAGE/DIRECTOR/MANAGER/HR/PERF_LEFT/PERF_RIGHT */
    private String sheetKind;
}
