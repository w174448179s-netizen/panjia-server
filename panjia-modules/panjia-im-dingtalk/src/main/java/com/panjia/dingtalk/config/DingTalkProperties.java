package com.panjia.dingtalk.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 钉钉集成配置（panjia.dingtalk）。
 * <p>
 * 凭证只从配置/环境变量读取，禁止硬编码进代码仓库。
 * 统一使用新版企业内部应用鉴权：clientId(AppKey) + clientSecret(AppSecret)
 * （{@code POST /v1.0/oauth2/accessToken}）。
 */
@Data
@Component
@ConfigurationProperties(prefix = "panjia.dingtalk")
public class DingTalkProperties {

    /** 总开关：false 时定时同步不执行、手动同步提示未启用 */
    private boolean enabled = false;

    /** 企业内部应用 AppKey */
    private String clientId;

    /** 企业内部应用 AppSecret */
    private String clientSecret;

    /** 应用 AgentId（预留，发工作通知用） */
    private String agentId;

    /** 每日同步 cron（考勤，默认每晚 23:30） */
    private String syncCron = "0 30 23 * * ?";

    /** 钉钉假期名称（考勤后台「假种」名称，需与企业实际配置一致） */
    private LeaveNames leaveNames = new LeaveNames();

    /**
     * 积分来源的钉钉日志模板名（二手、租赁两套日报模板）。
     * 模板名需与钉钉日志里完全一致，否则接口返回 40035「不合法的参数」。
     */
    private List<String> scoreTemplateNames = new ArrayList<>(List.of(
        "二手积分日报5.0版", "租赁积分日报3.0版"));

    /** 日志正文中的积分字段名（按包含匹配，兼容 emoji 前缀如「✨今日总积分」） */
    private String scoreKey = "今日总积分";

    /**
     * 假期名称映射：钉钉按名称取请假时长，名称不符会返回空。
     */
    @Data
    public static class LeaveNames {

        /** 事假 → personalLeaveDays */
        private String personal = "事假";

        /** 病假 → sickClinicDays（诊所类口径；医院类「病假（二甲医院以上）」需在系统内调整） */
        private String sick = "病假（诊所类证明）";
    }
}
