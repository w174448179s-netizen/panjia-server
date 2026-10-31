package com.panjia.dingtalk.client;

import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * 钉钉日志（积分日报）客户端。
 * <p>
 * 数据来源：{@code POST https://oapi.dingtalk.com/topapi/report/list}
 * （权限：查询企业员工日志权限，模板如「二手积分日报5.0版」「租赁积分日报3.0版」）。
 * 按模板名 + 毫秒时间窗分页拉取全组织日志，从日志正文 contents 中取积分字段值。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DingTalkScoreClient {

    private static final String URL_REPORT_LIST = "https://oapi.dingtalk.com/topapi/report/list";

    /** 接口单页上限 20 条 */
    private static final int PAGE_SIZE = 20;

    /** 分页保护：最多 1000 页（2 万条日志） */
    private static final int MAX_PAGES = 1000;

    /** 业务时区：钉钉日志时间戳按北京时间归日，避免容器 UTC 导致跨日/跨月错位 */
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private final DingTalkAccessTokenClient tokenClient;

    /**
     * 一条积分日报日志。
     */
    public record ScoreLog(String userId, String creatorName, String deptName,
                           LocalDateTime submitTime, BigDecimal score, String reportId) {
    }

    /**
     * 拉取指定月份、指定模板的积分日报日志。
     *
     * @param templateNames 日志模板名列表
     * @param scoreKey      积分字段名（按包含匹配，如「今日总积分」）
     * @param month         月份（时间窗为月初 00:00 至下月 1 日 00:00）
     * @return 日志列表（可能为空）
     */
    public List<ScoreLog> fetchMonth(List<String> templateNames, String scoreKey, YearMonth month) {
        long start = month.atDay(1).atStartOfDay(ZONE).toInstant().toEpochMilli();
        long end = month.atEndOfMonth().plusDays(1).atStartOfDay(ZONE).toInstant().toEpochMilli();
        List<ScoreLog> logs = new ArrayList<>();
        for (String templateName : templateNames) {
            logs.addAll(fetchTemplate(templateName, scoreKey, start, end));
        }
        log.info("[钉钉积分] {} 模板 {} 日志共 {} 条", month, templateNames, logs.size());
        return logs;
    }

    private List<ScoreLog> fetchTemplate(String templateName, String scoreKey, long start, long end) {
        List<ScoreLog> logs = new ArrayList<>();
        long cursor = 0L;
        for (int page = 0; page < MAX_PAGES; page++) {
            JSONObject json = post(templateName, start, end, cursor);
            JSONObject result = json.getJSONObject("result");
            if (result == null) {
                break;
            }
            JSONArray list = result.getJSONArray("data_list");
            if (list != null) {
                for (Object item : list) {
                    JSONObject obj = (JSONObject) item;
                    String userId = obj.getStr("creator_id");
                    if (userId == null || userId.isBlank()) {
                        continue;
                    }
                    Long ts = obj.getLong("create_time");
                    if (ts == null) {
                        continue;
                    }
                    logs.add(new ScoreLog(
                        userId,
                        obj.getStr("creator_name"),
                        obj.getStr("dept_name"),
                        LocalDateTime.ofInstant(Instant.ofEpochMilli(ts), ZONE),
                        scoreOf(obj.getJSONArray("contents"), scoreKey),
                        obj.getStr("report_id")));
                }
            }
            boolean hasMore = Boolean.TRUE.equals(result.getBool("has_more", false));
            Long next = result.getLong("next_cursor");
            if (!hasMore || next == null || next <= cursor) {
                break;
            }
            cursor = next;
        }
        return logs;
    }

    /** 从日志正文中取积分字段值（key 按包含匹配，value 可能为空） */
    private BigDecimal scoreOf(JSONArray contents, String scoreKey) {
        if (contents == null || scoreKey == null) {
            return null;
        }
        for (Object item : contents) {
            JSONObject content = (JSONObject) item;
            String key = content.getStr("key");
            if (key == null || !key.contains(scoreKey)) {
                continue;
            }
            String value = content.getStr("value");
            if (value == null || value.isBlank()) {
                return null;
            }
            try {
                return new BigDecimal(value.trim());
            } catch (NumberFormatException e) {
                log.warn("[钉钉积分] 日志积分字段非数字：{}", value);
                return null;
            }
        }
        return null;
    }

    private JSONObject post(String templateName, long start, long end, long cursor) {
        HttpResponse response = HttpRequest.post(URL_REPORT_LIST)
            .form("access_token", tokenClient.getToken())
            .form("template_name", templateName)
            .form("start_time", start)
            .form("end_time", end)
            .form("cursor", cursor)
            .form("size", PAGE_SIZE)
            .timeout(30000)
            .execute();
        String body = response.body();
        if (!response.isOk()) {
            throw new ServiceException("钉钉积分日志接口调用失败：status={}, body={}",
                response.getStatus(), body);
        }
        JSONObject json = JSONUtil.parseObj(body);
        int errcode = json.getInt("errcode", 0);
        if (errcode != 0) {
            throw new ServiceException("钉钉积分日志接口调用失败：模板={}，errcode={}，errmsg={}",
                templateName, errcode, json.getStr("errmsg"));
        }
        return json;
    }
}
