package com.panjia.people.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 员工变更日志实体（对应 pj_people_change_log）。
 * <p>
 * 员工档案任何写操作必须写日志（谁/何时/改了什么/旧值→新值），应用层显式写入（无触发器）。
 */
@Data
@TableName("pj_people_change_log")
public class ChangeLog implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 主键，雪花 ID */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 关联员工 ID */
    private Long employeeId;

    /** 变更类型（EmployeeChangeTypeEnum 枚举名） */
    private String changeType;

    /** 变更字段名（UPDATE 时） */
    private String fieldName;

    /** 旧值（JSON 序列化） */
    private String oldValue;

    /** 新值（JSON 序列化） */
    private String newValue;

    /** 变更原因 */
    private String changeReason;

    /** 操作人 login_name */
    private String operator;

    /** 操作时间 */
    private LocalDateTime operatedAt;

    /**
     * 工厂方法：构造变更日志。
     *
     * @param employeeId   员工 ID
     * @param changeType   变更类型枚举
     * @param fieldName    变更字段名（可空）
     * @param oldValue     旧值（可空）
     * @param newValue     新值（可空）
     * @param changeReason 变更原因
     * @param operator     操作人
     * @return 变更日志实例
     */
    public static ChangeLog create(Long employeeId, EmployeeChangeTypeEnum changeType,
                                   String fieldName, String oldValue, String newValue,
                                   String changeReason, String operator) {
        ChangeLog log = new ChangeLog();
        log.setEmployeeId(employeeId);
        log.setChangeType(changeType.name());
        log.setFieldName(fieldName);
        log.setOldValue(oldValue);
        log.setNewValue(newValue);
        log.setChangeReason(changeReason);
        log.setOperator(operator);
        log.setOperatedAt(LocalDateTime.now());
        return log;
    }
}
