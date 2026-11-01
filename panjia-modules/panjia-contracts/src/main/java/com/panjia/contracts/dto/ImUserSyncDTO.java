package com.panjia.contracts.dto;

import lombok.Data;

import java.io.Serializable;

/**
 * IM 账号同步入参（渠道无关）。
 * <p>
 * 钉钉/企微/飞书适配器把通讯录人员转换为本结构，
 * 由员工域按 {@link #externalCode}（工号）匹配本地员工后落库。
 * 渠道与企业由调用方按参数传入，不在每行重复。
 */
@Data
public class ImUserSyncDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 渠道内用户唯一标识（钉钉 userid、企微 userid 等） */
    private String externalUserId;

    /** 开放平台 unionid（可空） */
    private String externalUnionId;

    /** 渠道侧姓名 */
    private String externalName;

    /** 渠道侧手机号 */
    private String externalMobile;

    /** 渠道侧工号（匹配本地员工 employee_code 用，可空） */
    private String externalCode;

    /** 渠道侧是否在职；空按在职处理 */
    private Boolean externalActive;
}
