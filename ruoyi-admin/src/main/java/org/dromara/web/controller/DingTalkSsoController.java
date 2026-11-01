package org.dromara.web.controller;

import cn.dev33.satoken.annotation.SaIgnore;
import cn.dev33.satoken.stp.StpUtil;
import cn.dev33.satoken.stp.parameter.SaLoginParameter;
import cn.hutool.core.util.ObjectUtil;
import com.panjia.contracts.port.DingTalkAuthPort;
import com.panjia.contracts.port.ImUserAuthPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.constant.Constants;
import org.dromara.common.core.constant.SystemConstants;
import org.dromara.common.core.domain.R;
import org.dromara.common.core.utils.ServletUtils;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.system.api.model.LoginUser;
import org.dromara.system.domain.vo.SysClientVo;
import org.dromara.system.domain.vo.SysUserVo;
import org.dromara.system.mapper.SysUserMapper;
import org.dromara.system.service.ISysClientService;
import org.dromara.web.domain.DingTalkLoginBody;
import org.dromara.web.domain.vo.LoginVo;
import org.dromara.web.service.IAuthStrategy;
import org.dromara.web.service.SysLoginService;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 钉钉免登录（移动端 H5 微应用 + PC 端 OAuth2）。
 * <p>
 * 移动端：钉钉打开应用 → 前端 dd.runtime.permission.requestAuthCode 拿 code → /login <br>
 * PC 端：前端跳转 login.dingtalk.com 授权 → 回调带 code → /oauth-login <br>
 * 两条链路最终都用 code 换钉钉 userid → 查 pj_people_im_mapping 绑定的系统登录账号
 * → 生成 sa-token 登录态，前端拿到 access_token 后即可进入后台。
 */
@Slf4j
@SaIgnore
@RequiredArgsConstructor
@RestController
@RequestMapping("/auth/dingtalk")
public class DingTalkSsoController {

    /** IM 渠道标识 */
    private static final String CHANNEL_DINGTALK = "DINGTALK";

    private final DingTalkAuthPort dingTalkAuthPort;
    private final ImUserAuthPort imUserAuthPort;
    private final SysLoginService loginService;
    private final ISysClientService clientService;
    private final SysUserMapper userMapper;

    /**
     * 前端获取免登所需配置：CorpId（移动端 JSAPI）+ AppKey（PC 端 OAuth2 授权链接）。
     */
    @GetMapping("/config")
    public R<Map<String, String>> config() {
        return R.ok(Map.of("corp_id", dingTalkAuthPort.corpId(), "app_key", dingTalkAuthPort.appKey()));
    }

    /**
     * 移动端 H5 微应用免登：JSAPI code 换 userid，userid 换系统登录用户，签发 token。
     */
    @PostMapping("/login")
    public R<LoginVo> login(@RequestBody DingTalkLoginBody body) {
        if (ObjectUtil.isNull(body) || StringUtils.isBlank(body.getCode())) {
            return R.fail("缺少钉钉免登授权码");
        }
        // 1. code → 钉钉 userid（失败会抛出带钉钉错误信息的异常）
        return issueToken(dingTalkAuthPort.resolveUserId(body.getCode()), body.getClientId());
    }

    /**
     * PC 端 OAuth2 免登：授权回调 code 换 userid，userid 换系统登录用户，签发 token。
     */
    @PostMapping("/oauth-login")
    public R<LoginVo> oauthLogin(@RequestBody DingTalkLoginBody body) {
        if (ObjectUtil.isNull(body) || StringUtils.isBlank(body.getCode())) {
            return R.fail("缺少钉钉授权码");
        }
        // 1. code → unionId → 钉钉 userid（失败会抛出带钉钉错误信息的异常）
        return issueToken(dingTalkAuthPort.resolveUserIdByOAuthCode(body.getCode()), body.getClientId());
    }

    /**
     * userid → 系统登录用户 → 签发 token。
     */
    private R<LoginVo> issueToken(String externalUserId, String clientId) {
        if (StringUtils.isBlank(clientId)) {
            return R.fail("缺少客户端标识");
        }
        SysClientVo client = clientService.queryByClientId(clientId);
        if (ObjectUtil.isNull(client) || !SystemConstants.NORMAL.equals(client.getStatus())) {
            return R.fail("客户端配置无效");
        }

        // 2. userid → 系统登录用户（依赖「钉钉账号」映射）
        Long userId = imUserAuthPort.findBoundUserId(CHANNEL_DINGTALK, dingTalkAuthPort.corpId(), externalUserId);
        if (ObjectUtil.isNull(userId)) {
            return R.fail("你的钉钉账号还未绑定系统账号，请联系管理员在「钉钉账号」中完成绑定");
        }
        SysUserVo user = userMapper.selectVoById(userId);
        if (ObjectUtil.isNull(user)) {
            return R.fail("绑定的系统账号不存在");
        }
        if (SystemConstants.DISABLE.equals(user.getStatus())) {
            return R.fail("绑定的系统账号已停用");
        }

        // 3. 构建登录态并签发 token
        LoginUser loginUser = loginService.buildLoginUser(user);
        loginUser.setClientKey(client.getClientKey());
        loginUser.setDeviceType(client.getDeviceType());
        SaLoginParameter model = IAuthStrategy.buildLoginParameter(client);
        LoginHelper.login(loginUser, model);

        loginService.updateLastLoginInfo(userId, ServletUtils.getClientIP());
        loginService.recordLoginInfo(user.getUserName(), Constants.LOGIN_SUCCESS, "钉钉免登成功");

        LoginVo loginVo = new LoginVo();
        loginVo.setAccessToken(StpUtil.getTokenValue());
        loginVo.setExpireIn(StpUtil.getTokenTimeout());
        loginVo.setClientId(client.getClientId());
        log.info("[钉钉免登] userid={}, userId={}, userName={}", externalUserId, userId, user.getUserName());
        return R.ok(loginVo);
    }
}
