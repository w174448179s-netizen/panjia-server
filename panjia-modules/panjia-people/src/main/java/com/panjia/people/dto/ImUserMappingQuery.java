package com.panjia.people.dto;

import lombok.Data;

/**
 * IM 账号映射列表筛选条件。
 */
@Data
public class ImUserMappingQuery {

    /** 渠道：DINGTALK / WECOM / FEISHU；为空不限制 */
    private String channel;

    /** 匹配状态：MATCHED=已匹配员工 / UNMATCHED=未匹配；为空不限制 */
    private String matchStatus;

    /** 关键字：渠道姓名 / 工号 / 手机号 / 渠道 userid 模糊匹配 */
    private String keyword;

    /** 渠道侧在职：true=在职 / false=离职；为空不限制 */
    private Boolean active;
}
