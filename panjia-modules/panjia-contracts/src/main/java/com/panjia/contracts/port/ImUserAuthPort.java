package com.panjia.contracts.port;

/**
 * IM 账号免登查询端口：由 people 域实现，供 Web 认证层按渠道账号定位系统登录用户。
 */
public interface ImUserAuthPort {

    /**
     * 按渠道账号查绑定的系统登录用户 ID。
     *
     * @param channel        渠道标识（DINGTALK / WECOM / FEISHU）
     * @param corpId         企业 ID（可为空串）
     * @param externalUserId 渠道侧用户 ID（钉钉 userid）
     * @return 系统登录用户 ID；未绑定或员工未关联登录账号时返回 null
     */
    Long findBoundUserId(String channel, String corpId, String externalUserId);
}
