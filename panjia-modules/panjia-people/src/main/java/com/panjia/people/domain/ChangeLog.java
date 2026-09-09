package com.panjia.people.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 员工变更审计日志（对应 pj_people_change_log 表，一变更一行）。
 * <p>
 * 入职初始化时 change_field='ALL'；后续每次 changeFact 写一条，
 * 记录前后值、生效日与操作人。
 */
@Data
@TableName("pj_people_change_log")
public class ChangeLog implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 日志 ID，雪花 ID */
    @TableId(type = IdType.ASSIGN_ID)
    private Long logId;

    /** 员工 ID */
    private Long employeeId;

    /** 变更项（fact_type 编码 / ALL） */
    private String changeField;

    /** 变更前值 */
    private String beforeValue;

    /** 变更后值 */
    private String afterValue;

    /** 生效日期 */
    private LocalDate effectiveDate;

    /** 该变更记录的结束时间（被下一次变更闭区间时回填） */
    private LocalDate expireDate;

    /** 操作人 ID（sys_user.user_id） */
    private Long operatorId;

    /** 创建时间（DB 默认填充） */
    private LocalDateTime createTime;
}
