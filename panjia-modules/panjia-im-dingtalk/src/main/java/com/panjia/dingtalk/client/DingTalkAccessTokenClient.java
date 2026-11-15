package com.panjia.dingtalk.client;

import cn.hutool.http.HttpRequest;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.panjia.dingtalk.config.DingTalkConfigProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.StringUtils;
import org.springframework.stereotype.Component;

/**
 * 钉钉 access_token 获取与缓存。
 * <p>
 * 仅使用新版企业内部应用鉴权（AppKey + AppSecret）。
 * token 缓存在内存，提前 5 分钟过期，避免临界失效。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DingTalkAccessTokenClient {

    private static final String URL_TOKEN = "https://api.dingtalk.com/v1.0/oauth2/accessToken";

    /** 提前过期时间：5 分钟 */
    private static final long ADVANCE_EXPIRE_MILLIS = 5 * 60 * 1000L;

    private final DingTalkConfigProvider configProvider;

    /** 缓存的 access_token */
    private volatile String accessToken;

    /** 缓存失效时间（epoch millis） */
    private volatile long expireAt;

    /**
     * 取 access_token（缓存命中直接返回）。
     */
    public synchronized String getToken() {
        long now = System.currentTimeMillis();
        if (accessToken != null && now < expireAt) {
            return accessToken;
        }
        String token = fetchToken();
        // 默认 2 小时，提前 5 分钟失效
        this.accessToken = token;
        this.expireAt = now + 7200_000L - ADVANCE_EXPIRE_MILLIS;
        return token;
    }

    /**
     * 新版企业内部应用鉴权：POST /v1.0/oauth2/accessToken。
     */
    private String fetchToken() {
        String clientId = configProvider.clientId();
        String clientSecret = configProvider.clientSecret();
        if (StringUtils.isBlank(clientId) || StringUtils.isBlank(clientSecret)) {
            throw new ServiceException("钉钉未配置 client-id / client-secret，无法获取 access_token");
        }
        JSONObject req = new JSONObject();
        req.set("appKey", clientId);
        req.set("appSecret", clientSecret);
        String body = HttpRequest.post(URL_TOKEN)
            .body(req.toString())
            .timeout(15000)
            .execute()
            .body();
        JSONObject json = JSONUtil.parseObj(body);
        String token = json.getStr("accessToken");
        if (StringUtils.isBlank(token)) {
            throw new ServiceException("钉钉获取 access_token 失败：{}", body);
        }
        return token;
    }
}
