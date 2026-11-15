package org.dromara.web.domain;

import lombok.Data;

/**
 * 钉钉 H5 微应用免登请求体。
 */
@Data
public class DingTalkLoginBody {

    /** 钉钉前端 dd.runtime.permission.requestAuthCode 获取的免登授权码 */
    private String code;

    /** 前端客户端 ID（与账号密码登录一致） */
    private String clientId;
}
