package com.panjia.people.domain;

import com.fasterxml.jackson.annotation.JsonValue;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 员工状态枚举（V5.2）。
 * <p>
 * 存储约定：DB 字段 status VARCHAR(16) 存 code（code 固定取枚举名），
 * MyBatis-Plus 默认按枚举 name() 映射；Jackson 经 {@link JsonValue @JsonValue} 输出 code。
 */
public enum EmployeeStatus {

    /** 在职（全职） */
    ACTIVE("在职"),

    /** 兼职 */
    PARTTIME("兼职"),

    /** 离职（账户禁用，无删除接口） */
    LEFT("离职"),

    /** 待入职 */
    PENDING("待入职");

    /** 状态码（DB / JSON 存储值，与枚举名一致） */
    private final String code;

    /** 状态描述 */
    private final String desc;

    EmployeeStatus(String desc) {
        this.code = name();
        this.desc = desc;
    }

    /**
     * 获取状态码。
     *
     * @return 状态码
     */
    @JsonValue
    public String getCode() {
        return code;
    }

    /**
     * 获取状态描述。
     *
     * @return 描述文本
     */
    public String getDesc() {
        return desc;
    }

    /**
     * 按 code 解析状态枚举。
     *
     * @param code 状态码
     * @return 状态枚举；code 为空或无法识别时返回 null
     */
    public static EmployeeStatus fromCode(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        for (EmployeeStatus status : values()) {
            if (status.code.equals(code)) {
                return status;
            }
        }
        return null;
    }

    /**
     * 按 code 集合批量解析状态枚举（多选查询用）。
     * <p>
     * 忽略空白与无法识别的 code，结果去重并保持入参顺序。
     *
     * @param codes 状态码集合
     * @return 状态枚举列表；入参为空时返回空列表
     */
    public static List<EmployeeStatus> fromCodes(Collection<String> codes) {
        if (codes == null || codes.isEmpty()) {
            return List.of();
        }
        Set<EmployeeStatus> parsed = new LinkedHashSet<>();
        for (String code : codes) {
            EmployeeStatus status = fromCode(code);
            if (status != null) {
                parsed.add(status);
            }
        }
        return new ArrayList<>(parsed);
    }
}
