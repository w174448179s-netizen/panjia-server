package com.panjia.dingtalk.client;

import cn.hutool.http.HttpRequest;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.StringJoiner;

/**
 * 钉钉考勤报表客户端（月度汇总 + 每日考勤结果）。
 * <p>
 * 数据来源为钉钉「智能考勤报表」：
 * <ul>
 *   <li>{@code /topapi/attendance/getattcolumns} 取报表列定义（alias → id）；</li>
 *   <li>{@code /topapi/attendance/getcolumnval} 取某人某月每列每日值，数值列求和、考勤结果列保留每日值；</li>
 *   <li>{@code /topapi/attendance/getleavetimebynames} 按假期名称取请假天数。</li>
 * </ul>
 * 汇总与明细在同一次 getcolumnval 请求内取回，避免每人两次请求。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DingTalkAttendanceClient {

    private static final String BASE = "https://oapi.dingtalk.com";
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /** 报表列：考勤结果（每日状态原文：正常/迟到/缺卡/旷工/请假/休息…） */
    public static final String COL_ATTEND_RESULT = "attend_result";

    private final DingTalkAccessTokenClient tokenClient;

    /** 报表列定义缓存：alias → columnId（列定义基本不变，进程内只取一次） */
    private volatile Map<String, Long> columnIdCache;

    /**
     * 某人某月的报表数据（一次请求取回）。
     */
    public record UserMonthData(Map<String, BigDecimal> columnSums, Map<LocalDate, String> dailyStatus) {
    }

    /**
     * 取某人某月的数值列合计与每日考勤结果。
     *
     * @param userId         钉钉 userid
     * @param numericAliases 需要求和的数值列 alias（如 attendance_days）
     * @param month          月份
     * @return 数值列月度合计 + 每日考勤结果（日期 → 状态原文）
     */
    public UserMonthData fetchMonth(String userId, Collection<String> numericAliases, YearMonth month) {
        Map<String, BigDecimal> sums = new LinkedHashMap<>();
        Map<LocalDate, String> statuses = new LinkedHashMap<>();
        numericAliases.forEach(alias -> sums.put(alias, BigDecimal.ZERO));

        Map<String, Long> allIds = columnIds();
        Map<Long, String> idToAlias = new LinkedHashMap<>();
        for (String alias : numericAliases) {
            Long id = allIds.get(alias);
            if (id == null) {
                log.warn("[钉钉考勤] 报表列不存在，alias={}，按 0 处理", alias);
                continue;
            }
            idToAlias.put(id, alias);
        }
        Long statusId = allIds.get(COL_ATTEND_RESULT);
        if (statusId != null) {
            idToAlias.put(statusId, COL_ATTEND_RESULT);
        }
        if (idToAlias.isEmpty()) {
            return new UserMonthData(sums, statuses);
        }

        StringJoiner columnIds = new StringJoiner(",");
        idToAlias.keySet().forEach(id -> columnIds.add(String.valueOf(id)));
        LocalDate[] window = window(month);
        JSONObject req = new JSONObject()
            .set("userid", userId)
            .set("column_id_list", columnIds.toString())
            .set("from_date", window[0].atStartOfDay().format(FMT))
            .set("to_date", window[1].atTime(23, 59, 59).format(FMT));
        JSONObject json = post("/topapi/attendance/getcolumnval", req);
        JSONObject result = json.getJSONObject("result");
        JSONArray columns = result == null ? null : result.getJSONArray("column_vals");
        if (columns == null) {
            return new UserMonthData(sums, statuses);
        }
        for (Object item : columns) {
            JSONObject column = (JSONObject) item;
            Long id = column.getJSONObject("column_vo").getLong("id");
            String alias = idToAlias.get(id);
            JSONArray values = column.getJSONArray("column_vals");
            if (alias == null || values == null) {
                continue;
            }
            if (COL_ATTEND_RESULT.equals(alias)) {
                for (Object value : values) {
                    JSONObject cell = (JSONObject) value;
                    String date = cell.getStr("date");
                    String text = cell.getStr("value");
                    if (date != null && text != null && !"null".equalsIgnoreCase(text) && !text.isBlank()) {
                        statuses.put(parseDate(date), text.trim());
                    }
                }
            } else {
                sums.put(alias, sumValues(values));
            }
        }
        return new UserMonthData(sums, statuses);
    }

    /**
     * 取某人某月按假期名称的请假天数合计。
     *
     * @param userId     钉钉 userid
     * @param leaveNames 假期名称（需与考勤后台假种一致）
     * @param month      月份
     * @return 假期名称 → 月度天数合计（名称不匹配时该名称为 0）
     */
    public Map<String, BigDecimal> leaveSums(String userId, Collection<String> leaveNames, YearMonth month) {
        Map<String, BigDecimal> sums = new LinkedHashMap<>();
        if (leaveNames.isEmpty()) {
            return sums;
        }
        StringJoiner names = new StringJoiner(",");
        leaveNames.forEach(names::add);
        LocalDate[] window = window(month);
        JSONObject req = new JSONObject()
            .set("userid", userId)
            .set("leave_names", names.toString())
            .set("from_date", window[0].atStartOfDay().format(FMT))
            .set("to_date", window[1].atTime(23, 59, 59).format(FMT));
        JSONObject json = post("/topapi/attendance/getleavetimebynames", req);
        JSONObject result = json.getJSONObject("result");
        JSONArray columns = result == null ? null : result.getJSONArray("columns");
        if (columns != null) {
            for (Object item : columns) {
                JSONObject column = (JSONObject) item;
                String name = column.getJSONObject("columnvo").getStr("name");
                if (name != null) {
                    sums.put(name, sumValues(column.getJSONArray("columnvals")));
                }
            }
        }
        return sums;
    }

    /**
     * 报表列定义 alias → id（进程内缓存）。
     */
    public synchronized Map<String, Long> columnIds() {
        if (columnIdCache != null) {
            return columnIdCache;
        }
        JSONObject json = post("/topapi/attendance/getattcolumns", new JSONObject());
        Map<String, Long> ids = new LinkedHashMap<>();
        JSONObject result = json.getJSONObject("result");
        JSONArray columns = result == null ? null : result.getJSONArray("columns");
        if (columns != null) {
            for (Object item : columns) {
                JSONObject column = (JSONObject) item;
                String alias = column.getStr("alias");
                Long id = column.getLong("id");
                if (alias != null && id != null) {
                    ids.put(alias, id);
                }
            }
        }
        columnIdCache = ids;
        log.info("[钉钉考勤] 报表列定义 {} 个", ids.size());
        return ids;
    }

    /**
     * 查询时间窗：整月；当月截止到今天（未来日期无数据）。
     */
    private LocalDate[] window(YearMonth month) {
        LocalDate from = month.atDay(1);
        LocalDate to = month.atEndOfMonth();
        LocalDate today = LocalDate.now();
        if (!month.atEndOfMonth().isBefore(today) && today.isAfter(from)) {
            to = today;
        }
        return new LocalDate[]{from, to};
    }

    private LocalDate parseDate(String text) {
        String value = text.trim();
        if (value.length() > 10) {
            return LocalDate.parse(value.substring(0, 10), DATE_FMT);
        }
        return LocalDate.parse(value, DATE_FMT);
    }

    private BigDecimal sumValues(JSONArray values) {
        if (values == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal sum = BigDecimal.ZERO;
        for (Object item : values) {
            String value = ((JSONObject) item).getStr("value");
            if (value == null || "null".equalsIgnoreCase(value) || value.isBlank()) {
                continue;
            }
            try {
                sum = sum.add(new BigDecimal(value));
            } catch (NumberFormatException e) {
                log.warn("[钉钉考勤] 无法解析报表列值：{}", value);
            }
        }
        return sum;
    }

    private JSONObject post(String path, JSONObject body) {
        String response = HttpRequest.post(BASE + path + "?access_token=" + tokenClient.getToken())
            .body(body.toString())
            .timeout(30000)
            .execute()
            .body();
        JSONObject json = JSONUtil.parseObj(response);
        int errcode = json.getInt("errcode", -1);
        if (errcode != 0) {
            throw new ServiceException("钉钉考勤报表接口调用失败：path={}, errcode={}, errmsg={}",
                path, errcode, json.getStr("errmsg"));
        }
        return json;
    }
}
