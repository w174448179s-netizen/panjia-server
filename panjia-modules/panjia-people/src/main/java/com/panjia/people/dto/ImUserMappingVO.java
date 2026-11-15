package com.panjia.people.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * IM 账号映射列表行（渠道无关）。
 */
@Data
public class ImUserMappingVO {

    /** 映射 ID */
    private Long mappingId;

    /** 渠道：DINGTALK / WECOM / FEISHU */
    private String channel;

    /** 企业标识 */
    private String corpId;

    /** 渠道内用户唯一标识（钉钉 userid） */
    private String externalUserId;

    /** 渠道侧姓名 */
    private String externalName;

    /** 渠道侧手机号 */
    private String externalMobile;

    /** 渠道侧工号 */
    private String externalCode;

    /** 渠道侧是否在职 */
    private Boolean externalActive;

    /** 本地员工 ID */
    private Long employeeId;

    /** 本地员工姓名 */
    private String employeeName;

    /** 本地登录账号 ID */
    private Long userId;

    /** 本地登录账号（登录名） */
    private String userName;

    /** 匹配方式：CODE/MOBILE/MANUAL/NONE */
    private String matchType;

    /** 最近一次同步时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime lastSyncTime;
}
