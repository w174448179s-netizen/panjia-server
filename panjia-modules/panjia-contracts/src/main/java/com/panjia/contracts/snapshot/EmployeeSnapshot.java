package com.panjia.contracts.snapshot;

import lombok.Data;

import java.math.BigDecimal;
import java.util.Date;

/**
 * 员工快照 POJO（示例字段，由消费方 Task-4/5 按需扩展）。
 * <p>
 * 用于结佣/算薪时冻结员工关键信息，保证历史可追溯；快照归消费域持久化，
 * 不归 people 域。
 */
@Data
public class EmployeeSnapshot implements Snapshot {

    /** 员工 ID（雪花） */
    private Long employeeId;

    /** 工号 */
    private String employeeCode;

    /** 姓名 */
    private String name;

    /** 职级（A0~A5、S1/S2、总监） */
    private String level;

    /** 社保基数 */
    private BigDecimal socialBase;

    /** 快照生成时间 */
    private Date snapshotAt;
}
