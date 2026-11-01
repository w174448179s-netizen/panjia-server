package com.panjia.contracts.port;

/**
 * 钉钉免登端口：由 im-dingtalk 域实现，供 Web 认证层使用。
 */
public interface DingTalkAuthPort {

    /** 企业 CorpId（H5 微应用获取免登授权码时使用，未配置返回空串） */
    String corpId();

    /** 应用 AppKey（PC 端拼接 OAuth2 授权链接用，未配置返回空串） */
    String appKey();

    /**
     * 用 H5 微应用的免登授权码换取钉钉 userid。
     *
     * @param code 钉钉前端 dd.runtime.permission.requestAuthCode 返回的 code
     * @return 钉钉 userid
     */
    String resolveUserId(String code);

    /**
     * 用 PC 端 OAuth2 授权码换取钉钉 userid（扫码或钉钉内一键确认登录）。
     *
     * @param code 钉钉 OAuth2 授权后回调带回的 code
     * @return 钉钉 userid
     */
    String resolveUserIdByOAuthCode(String code);
}
