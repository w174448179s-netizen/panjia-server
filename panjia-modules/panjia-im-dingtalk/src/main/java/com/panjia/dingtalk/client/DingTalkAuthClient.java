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
 * 钉钉 H5 微应用免登：用前端 requestAuthCode 拿到的 code 换钉钉 userid。
 * <p>
 * 接口：POST https://oapi.dingtalk.com/topapi/v2/user/getuserinfo?access_token=xxx
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DingTalkAuthClient implements DingTalkAuthPort {

    private static final String URL_GET_USER_INFO = "https://oapi.dingtalk.com/topapi/v2/user/getuserinfo";

    private final DingTalkAccessTokenClient tokenClient;
    private final DingTalkConfigProvider configProvider;

    @Override
    public String corpId() {
        return configProvider.corpId();
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
