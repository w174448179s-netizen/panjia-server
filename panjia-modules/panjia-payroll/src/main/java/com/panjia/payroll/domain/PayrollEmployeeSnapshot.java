package com.panjia.payroll.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** 员工快照（算薪时点冻结） */
@Data
@TableName("pj_payroll_employee_snapshot")
public class PayrollEmployeeSnapshot implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long batchId;
    private Long employeeId;
    private LocalDate snapshotDate;
    private String snapshotContent;
    private LocalDateTime createTime;
}
