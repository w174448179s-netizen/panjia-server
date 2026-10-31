package com.panjia.dingtalk.client;

import cn.hutool.http.HttpRequest;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.panjia.dingtalk.dto.DingTalkUserDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.StringUtils;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

/**
 * 钉钉通讯录客户端（部门树 + 全员）。
 * <p>
 * 流程：/topapi/v2/department/listsub 递归取部门树（含名称/父级），
 * 再逐部门 /topapi/v2/user/list 分页取成员；job_number 缺失时用 /topapi/v2/user/get 补齐。
 * 钉钉部门名按「门店-组别」拼全路径，供员工域匹配系统部门。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DingTalkContactClient {

    private static final String BASE = "https://oapi.dingtalk.com";
    /** 根部门 ID */
    private static final long ROOT_DEPT_ID = 1L;
    /** 分页大小 */
    private static final int PAGE_SIZE = 100;
    /** 部门树递归深度保护 */
    private static final int MAX_DEPT_DEPTH = 20;

    private final DingTalkAccessTokenClient tokenClient;

    /**
     * 取全员（按 userid 去重）。
     */
    public List<DingTalkUserDTO> listAllUsers() {
        Map<Long, String> deptNames = new HashMap<>();
        Map<Long, Long> deptParents = new HashMap<>();
        collectDepartments(ROOT_DEPT_ID, deptNames, deptParents, 0);

        Map<String, DingTalkUserDTO> users = new LinkedHashMap<>();
        fetchDeptUsers(ROOT_DEPT_ID, deptNames, deptParents, users);
        for (Long deptId : new ArrayList<>(deptNames.keySet())) {
            fetchDeptUsers(deptId, deptNames, deptParents, users);
        }

        // job_number 缺失的员工补齐（列表接口部分企业不返回工号）
        for (DingTalkUserDTO user : users.values()) {
            if (StringUtils.isBlank(user.getJobNumber())) {
                fillJobNumber(user);
            }
        }
        log.info("[钉钉通讯录] 部门 {} 个，人员 {} 人", deptNames.size(), users.size());
        return new ArrayList<>(users.values());
    }

    // ==================== 部门 ====================

    private void collectDepartments(Long deptId, Map<Long, String> names,
                                    Map<Long, Long> parents, int depth) {
        if (depth > MAX_DEPT_DEPTH) {
            log.warn("[钉钉通讯录] 部门层级超过 {} 层，停止递归：deptId={}", MAX_DEPT_DEPTH, deptId);
            return;
        }
        JSONObject req = new JSONObject().set("dept_id", deptId);
        JSONObject json = post("/topapi/v2/department/listsub", req);
        Object resultObj = json.get("result");
        if (resultObj == null) {
            return;
        }
        JSONArray children = resultObj instanceof JSONArray
            ? (JSONArray) resultObj
            : ((JSONObject) resultObj).getJSONArray("dept_id_list");
        if (children == null) {
            return;
        }
        for (Object item : children) {
            Long childId;
            if (item instanceof JSONObject) {
                JSONObject obj = (JSONObject) item;
                childId = obj.getLong("dept_id");
                if (childId == null) {
                    continue;
                }
                names.put(childId, obj.getStr("name"));
                parents.put(childId, obj.getLong("parent_id", deptId));
            } else {
                childId = Long.valueOf(item.toString());
                JSONObject detail = fetchDeptDetail(childId);
                if (detail == null) {
                    continue;
                }
                names.put(childId, detail.getStr("name"));
                parents.put(childId, detail.getLong("parent_id", deptId));
            }
            collectDepartments(childId, names, parents, depth + 1);
        }
    }

    private JSONObject fetchDeptDetail(Long deptId) {
        try {
            JSONObject json = post("/topapi/v2/department/get", new JSONObject().set("dept_id", deptId));
            Object result = json.get("result");
            return result instanceof JSONObject ? (JSONObject) result : null;
        } catch (Exception e) {
            log.warn("[钉钉通讯录] 部门详情获取失败：deptId={}, {}", deptId, e.getMessage());
            return null;
        }
    }

    /**
     * 拼部门全路径「门店-组别」，跳过根部门。
     */
    private String buildDeptPath(Long deptId, Map<Long, String> names, Map<Long, Long> parents) {
        LinkedList<String> parts = new LinkedList<>();
        Long current = deptId;
        int guard = 0;
        while (current != null && current > ROOT_DEPT_ID && guard++ < MAX_DEPT_DEPTH) {
            String name = names.get(current);
            if (name != null) {
                parts.addFirst(name);
            }
            current = parents.get(current);
        }
        return parts.isEmpty() ? null : String.join("-", parts);
    }

    // ==================== 人员 ====================

    private void fetchDeptUsers(Long deptId, Map<Long, String> names, Map<Long, Long> parents,
                                Map<String, DingTalkUserDTO> users) {
        String deptPath = buildDeptPath(deptId, names, parents);
        long cursor = 0L;
        int guard = 0;
        while (guard++ < 100) {
            JSONObject req = new JSONObject()
                .set("dept_id", deptId)
                .set("cursor", cursor)
                .set("size", PAGE_SIZE);
            JSONObject json = post("/topapi/v2/user/list", req);
            JSONObject result = json.getJSONObject("result");
            if (result == null) {
                return;
            }
            JSONArray list = result.getJSONArray("list");
            if (list != null) {
                for (Object item : list) {
                    JSONObject obj = (JSONObject) item;
                    String userId = obj.getStr("userid");
                    if (StringUtils.isBlank(userId)) {
                        continue;
                    }
                    DingTalkUserDTO user = users.computeIfAbsent(userId, k -> new DingTalkUserDTO());
                    user.setUserId(userId);
                    user.setName(obj.getStr("name"));
                    user.setMobile(obj.getStr("mobile"));
                    user.setJobNumber(obj.getStr("job_number"));
                    user.setActive(obj.getBool("active", true));
                    if (StringUtils.isBlank(user.getDeptFullName())) {
                        user.setDeptFullName(deptPath);
                    }
                }
            }
            if (!Boolean.TRUE.equals(result.getBool("has_more", false))) {
                return;
            }
            cursor = result.getLong("next_cursor", cursor + PAGE_SIZE);
        }
    }

    private void fillJobNumber(DingTalkUserDTO user) {
        try {
            JSONObject json = post("/topapi/v2/user/get",
                new JSONObject().set("userid", user.getUserId()).set("language", "zh_CN"));
            JSONObject result = json.getJSONObject("result");
            if (result != null) {
                user.setJobNumber(result.getStr("job_number"));
                if (StringUtils.isBlank(user.getMobile())) {
                    user.setMobile(result.getStr("mobile"));
                }
                if (StringUtils.isBlank(user.getName())) {
                    user.setName(result.getStr("name"));
                }
            }
        } catch (Exception e) {
            log.warn("[钉钉通讯录] 工号补齐失败：userid={}, {}", user.getUserId(), e.getMessage());
        }
    }

    // ==================== HTTP ====================

    private JSONObject post(String path, JSONObject req) {
        String url = BASE + path + "?access_token=" + tokenClient.getToken();
        String body = HttpRequest.post(url).body(req.toString()).timeout(20000).execute().body();
        JSONObject json = JSONUtil.parseObj(body);
        int errcode = json.getInt("errcode", -1);
        if (errcode != 0) {
            throw new ServiceException("钉钉接口 {} 调用失败：errcode={}, errmsg={}",
                path, errcode, json.getStr("errmsg"));
        }
        return json;
    }
}
