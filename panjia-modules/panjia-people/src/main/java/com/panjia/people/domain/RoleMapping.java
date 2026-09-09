package com.panjia.people.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 业务角色 → 系统角色映射实体（对应 pj_people_role_mapping）。
 * <p>
 * 多对多关系：一个业务角色可映射多个 sys_role_id（如 STORE_MANAGER → 店长 + 算薪人员）。
 * 支持热改：不同客户角色体系不同，改配置不改代码。
 */
@Data
@TableName("pj_people_role_mapping")
public class RoleMapping implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 主键，雪花 ID */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 业务角色 code：AGENT / STORE_MANAGER / DIRECTOR */
    private String employeeRole;

    /** 关联 sys_role.role_id */
    private Long sysRoleId;

    /** 乐观锁版本号 */
    @Version
    private Integer optLockVersion;

    /** 是否启用 */
    private Boolean isActive;

    /** 备注 */
    private String remark;

    /** 创建人 */
    private String createdBy;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 更新人 */
    private String updatedBy;

    /** 更新时间 */
    private LocalDateTime updatedAt;
}
