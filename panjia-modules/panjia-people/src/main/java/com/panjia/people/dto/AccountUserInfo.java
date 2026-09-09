package com.panjia.people.dto;

import lombok.Data;

import java.util.List;

/**
 * 系统账户读模型（对账/列表展示用，隔离 sys_user 实体不泄露到 people service）。
 */
@Data
public class AccountUserInfo {

    /** 系统用户 ID */
    private Long userId;

    /** 账户归属部门 ID */
    private Long deptId;

    /** 账户是否启用（sys_user.status = '0'） */
    private Boolean enabled;

    /** 当前绑定的岗位 ID 集合 */
    private List<Long> postIds;

    /** 当前绑定的岗位名集合 */
    private List<String> postNames;
}
