package com.panjia.dingtalk.config;

import lombok.RequiredArgsConstructor;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.system.api.ConfigService;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 钉钉集成配置读取：全部来自参数管理（系统管理 → 参数设置）的 panjia.dingtalk.* 参数。
 * <p>
 * 参数改完即时生效（走 RuoYi 参数缓存）。参数缺失或为空时使用代码内置默认值，
 * 保证功能不会因为漏配一个参数整块不可用；凭证类参数（client_id / client_secret）
 * 没有默认值，未配置时同步会给出明确提示。
 */
@Component
@RequiredArgsConstructor
public class DingTalkConfigProvider {

    /** 总开关：true 时允许手动同步与定时同步 */
    public static final String KEY_ENABLED = "panjia.dingtalk.enabled";

    /** 企业内部应用 AppKey */
    public static final String KEY_CLIENT_ID = "panjia.dingtalk.client_id";

    /** 企业内部应用 AppSecret */
    public static final String KEY_CLIENT_SECRET = "panjia.dingtalk.client_secret";

    /** 应用 AgentId */
    public static final String KEY_AGENT_ID = "panjia.dingtalk.agent_id";

    /** 事假名称（钉钉考勤后台「假种」名称） */
    public static final String KEY_LEAVE_PERSONAL = "panjia.dingtalk.leave_name_personal";

    /** 病假名称（诊所类口径） */
    public static final String KEY_LEAVE_SICK = "panjia.dingtalk.leave_name_sick";

    /** 积分日报日志模板名（多值逗号分隔） */
    public static final String KEY_SCORE_TEMPLATES = "panjia.dingtalk.score_template_names";

    /** 日志正文中的积分字段名（按包含匹配） */
    public static final String KEY_SCORE_KEY = "panjia.dingtalk.score_key";

    private static final String DEFAULT_LEAVE_PERSONAL = "事假";

    private static final String DEFAULT_LEAVE_SICK = "病假（诊所类证明）";

    private static final List<String> DEFAULT_SCORE_TEMPLATES =
        List.of("二手积分日报5.0版", "租赁积分日报3.0版");

    private static final String DEFAULT_SCORE_KEY = "今日总积分";

    private final ConfigService configService;

    /** 总开关：未配置或空值视为关闭 */
    public boolean enabled() {
        String value = text(KEY_ENABLED);
        if (StringUtils.isBlank(value)) {
            return false;
        }
        String normalized = value.trim().toLowerCase();
        return "true".equals(normalized) || "1".equals(normalized) || "y".equals(normalized);
    }

    /** 应用 AppKey（未配置为空） */
    public String clientId() {
        return orBlank(text(KEY_CLIENT_ID), null);
    }

    /** 应用 AppSecret（未配置为空） */
    public String clientSecret() {
        return orBlank(text(KEY_CLIENT_SECRET), null);
    }

    /** 应用 AgentId（未配置为空） */
    public String agentId() {
        return orBlank(text(KEY_AGENT_ID), null);
    }

    /** 事假名称 */
    public String leaveNamePersonal() {
        return orBlank(text(KEY_LEAVE_PERSONAL), DEFAULT_LEAVE_PERSONAL);
    }

    /** 病假名称（诊所类证明口径） */
    public String leaveNameSick() {
        return orBlank(text(KEY_LEAVE_SICK), DEFAULT_LEAVE_SICK);
    }

    /** 积分日报模板名：参数用逗号分隔，未配置时用默认两套模板 */
    public List<String> scoreTemplateNames() {
        String value = text(KEY_SCORE_TEMPLATES);
        if (StringUtils.isBlank(value)) {
            return DEFAULT_SCORE_TEMPLATES;
        }
        List<String> names = new ArrayList<>();
        for (String part : value.split("[,，]")) {
            if (StringUtils.isNotBlank(part)) {
                names.add(part.trim());
            }
        }
        return names.isEmpty() ? DEFAULT_SCORE_TEMPLATES : names;
    }

    /** 日志正文中的积分字段名 */
    public String scoreKey() {
        return orBlank(text(KEY_SCORE_KEY), DEFAULT_SCORE_KEY);
    }

    /** 读取参数原始值 */
    private String text(String key) {
        return configService.getConfigValue(key);
    }

    /** 空值回退默认 */
    private String orBlank(String value, String fallback) {
        return StringUtils.isBlank(value) ? fallback : value.trim();
    }
}
