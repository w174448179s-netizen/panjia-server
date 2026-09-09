package com.panjia.people.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 员工主数据（对应 pj_people_employee 表，一人一行）。
 * <p>
 * V5.2 约定：不存岗位/角色——岗位角色全部走 sys_user_post / sys_user_role，
 * Employee 只存归属部门 dept_id 与关联账户 user_id。
 * <p>
 * 不继承 RuoYi BaseEntity：本表无 create_by/update_by 审计列，
 * create_time/update_time 由数据库默认值填充。
 */
@Data
@TableName("pj_people_employee")
public class Employee implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 员工 ID，雪花 ID */
    @TableId(type = IdType.ASSIGN_ID)
    private Long employeeId;

    /** 工号（唯一，= sys_user.user_name） */
    private String employeeCode;

    /** 姓名 */
    private String employeeName;

    /** 归属部门 ID（= sys_user.dept_id） */
    private Long deptId;

    /** 电话 */
    private String phone;

    /** 身份证号（AES 加密存储） */
    private String idCard;

    /** 报道时间 */
    private LocalDate reportDate;

    /** 入职时间（fact 生效日） */
    private LocalDate hireDate;

    /** 离职时间（null=未离职） */
    private LocalDate leaveDate;

    /** 状态：ACTIVE/PARTTIME/LEFT/PENDING */
    private EmployeeStatus status;

    /** 师傅员工 ID（null=无师傅） */
    private Long mentorEmployeeId;

    /** 备注 */
    private String remark;

    /** 关联 sys_user.user_id（建账户后回填） */
    private Long userId;

    /** 乐观锁版本号 */
    @Version
    private Integer version;

    /** 创建时间（DB 默认填充） */
    private LocalDateTime createTime;

    /** 更新时间（DB 默认填充） */
    private LocalDateTime updateTime;
}
