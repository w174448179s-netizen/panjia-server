package com.panjia.dingtalk.dto;

import lombok.Data;

/**
 * 钉钉通讯录人员（同步中间模型）。
 */
@Data
public class DingTalkUserDTO {

    /** 钉钉 userid */
    private String userId;

    /** 姓名 */
    private String name;

    /** 手机号 */
    private String mobile;

    /** 工号（job_number），为空表示钉钉侧未维护 */
    private String jobNumber;

    /** 是否在职 */
    private Boolean active;

    /** 所属部门全路径（"门店-组别"，取 dept_id_list 首个部门） */
    private String deptFullName;
}
