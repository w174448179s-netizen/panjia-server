package com.panjia.people.domain;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 员工导入问题类型（V6.0 §5.4）。
 * <p>
 * 除标注外均为阻断性问题：存在任一阻断问题则批次 FAILED，不进入落地阶段。
 * 存储约定：DB 字段 issue_type VARCHAR(32) 存 code（code 固定取枚举名）。
 */
public enum PeopleImportIssueType {

    /** 必填缺失（工具层基础校验） */
    REQUIRED_MISSING("必填缺失", true),

    /** 类型转换失败（工具层解析/基础校验） */
    COLUMN_TYPE_ERR("格式错误", true),

    /** 工号重复（文件内重复 + 库内已存在） */
    DUPLICATE_CODE("工号重复", true),

    /** 部门路径非法（"门店-组别" 路径格式不符） */
    DEPT_PATH_INVALID("部门路径非法", true),

    /** 职级不在枚举白名单 */
    LEVEL_INVALID("职级非法", true),

    /** 师傅工号不存在（库内与本批次均无此工号） */
    MENTOR_NOT_FOUND("师傅不存在", true);

    /** 类型码（DB / JSON 存储值，与枚举名一致） */
    private final String code;

    /** 类型描述 */
    private final String desc;

    /** 是否阻断导入 */
    private final boolean blocking;

    PeopleImportIssueType(String desc, boolean blocking) {
        this.code = name();
        this.desc = desc;
        this.blocking = blocking;
    }

    /**
     * 获取类型码。
     *
     * @return 类型码
     */
    @JsonValue
    public String getCode() {
        return code;
    }

    /**
     * 获取类型描述。
     *
     * @return 描述文本
     */
    public String getDesc() {
        return desc;
    }

    /**
     * 是否阻断性问题。
     *
     * @return true 表示阻断
     */
    public boolean isBlocking() {
        return blocking;
    }

    /**
     * 按 code 解析类型枚举。
     *
     * @param code 类型码
     * @return 类型枚举；code 为空或无法识别时返回 null
     */
    public static PeopleImportIssueType fromCode(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        for (PeopleImportIssueType type : values()) {
            if (type.code.equals(code)) {
                return type;
            }
        }
        return null;
    }
}
