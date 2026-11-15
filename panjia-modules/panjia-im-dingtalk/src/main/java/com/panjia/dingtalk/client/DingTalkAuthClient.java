package com.panjia.dingtalk.client;

import cn.hutool.http.HttpRequest;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.panjia.contracts.port.DingTalkAuthPort;
import com.panjia.dingtalk.config.DingTalkConfigProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.StringUtils;
import org.springframework.stereotype.Component;

/**
 * 钉钉免登：把授权码换成钉钉 userid。
 * <p>
 * 移动端（H5 微应用）：POST https://oapi.dingtalk.com/topapi/v2/user/getuserinfo <br>
 * PC 端（OAuth2 授权）：userAccessToken → contact/users/me 取 unionId → topapi/user/getbyunionid 取 userid
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DingTalkAuthClient implements DingTalkAuthPort {

    private static final String URL_GET_USER_INFO = "https://oapi.dingtalk.com/topapi/v2/user/getuserinfo";

    private static final String URL_USER_ACCESS_TOKEN = "https://api.dingtalk.com/v1.0/oauth2/userAccessToken";

    private static final String URL_USERS_ME = "https://api.dingtalk.com/v1.0/contact/users/me";

    private static final String URL_GET_BY_UNIONID = "https://oapi.dingtalk.com/topapi/user/getbyunionid";

    private final DingTalkAccessTokenClient tokenClient;
    private final DingTalkConfigProvider configProvider;

    @Override
    public String corpId() {
        return configProvider.corpId();
    }

    @Override
    public String appKey() {
        String clientId = configProvider.clientId();
        return clientId == null ? "" : clientId;
    }

    @Override
    public String resolveUserIdByOAuthCode(String code) {
        if (StringUtils.isBlank(code)) {
            throw new ServiceException("缺少钉钉授权码");
        }
        String unionId = fetchUnionId(code);
        String accessToken = tokenClient.getToken();
        String body = new JSONObject().set("unionid", unionId).toString();
        String response = HttpRequest.post(URL_GET_BY_UNIONID + "?access_token=" + accessToken)
            .body(body)
            .timeout(15000)
            .execute()
            .body();
        JSONObject json = JSONUtil.parseObj(response);
        int errcode = json.getInt("errcode", -1);
        if (errcode != 0) {
            log.warn("[钉钉 PC 免登] unionId 换 userid 失败 unionId={}, response={}", unionId, response);
            throw new ServiceException("钉钉 PC 登录失败：{}", json.getStr("errmsg", response));
        }
        JSONObject result = json.getJSONObject("result");
        String userid = result == null ? null : result.getStr("userid");
        if (StringUtils.isBlank(userid)) {
            throw new ServiceException("钉钉 PC 登录失败：未返回 userid");
        }
        return userid;
    }

    /** OAuth2 授权码 → 用户 access_token → unionId */
    private String fetchUnionId(String code) {
        String clientId = configProvider.clientId();
        String clientSecret = configProvider.clientSecret();
        if (StringUtils.isBlank(clientId) || StringUtils.isBlank(clientSecret)) {
            throw new ServiceException("钉钉未配置 client-id / client-secret，无法完成 PC 免登");
        }
        JSONObject request = new JSONObject()
            .set("clientId", clientId)
            .set("clientSecret", clientSecret)
            .set("code", code)
            .set("grantType", "authorization_code");
        String tokenResponse = HttpRequest.post(URL_USER_ACCESS_TOKEN)
            .body(request.toString())
            .timeout(15000)
            .execute()
            .body();
        JSONObject tokenJson = JSONUtil.parseObj(tokenResponse);
        String userAccessToken = tokenJson.getStr("accessToken");
        if (StringUtils.isBlank(userAccessToken)) {
            log.warn("[钉钉 PC 免登] 换取用户 token 失败 code={}, response={}", code, tokenResponse);
            throw new ServiceException("钉钉 PC 登录失败：{}", tokenJson.getStr("message", tokenResponse));
        }
        String meResponse = HttpRequest.get(URL_USERS_ME)
            .header("x-acs-dingtalk-access-token", userAccessToken)
            .timeout(15000)
            .execute()
            .body();
        JSONObject me = JSONUtil.parseObj(meResponse);
        String unionId = me.getStr("unionId");
        if (StringUtils.isBlank(unionId)) {
            log.warn("[钉钉 PC 免登] 未取到 unionId, response={}", meResponse);
            throw new ServiceException("钉钉 PC 登录失败：未返回 unionId");
        }
        return unionId;
    }

    @Override
    public String resolveUserId(String code) {
        if (StringUtils.isBlank(code)) {
            throw new ServiceException("缺少钉钉免登授权码");
        }
        String accessToken = tokenClient.getToken();
        String body = new JSONObject().set("code", code).toString();
        String response = HttpRequest.post(URL_GET_USER_INFO + "?access_token=" + accessToken)
            .body(body)
            .timeout(15000)
            .execute()
            .body();
        JSONObject json = JSONUtil.parseObj(response);
        int errcode = json.getInt("errcode", -1);
        if (errcode != 0) {
            log.warn("[钉钉免登] 换取 userid 失败 code={}, response={}", code, response);
            throw new ServiceException("钉钉免登失败：{}", json.getStr("errmsg", response));
        }
        JSONObject result = json.getJSONObject("result");
        String userid = result == null ? null : result.getStr("userid");
        if (StringUtils.isBlank(userid)) {
            throw new ServiceException("钉钉免登失败：未返回 userid");
        }
        return userid;
    }
}
