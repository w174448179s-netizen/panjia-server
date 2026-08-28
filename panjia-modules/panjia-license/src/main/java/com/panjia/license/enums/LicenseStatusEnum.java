package com.panjia.license.enums;

/**
 * 授权状态枚举。
 * 对应离线模式三种状态：离线宽限期 / 离线锁死 / 受限模式。
 */
public enum LicenseStatusEnum {

    /** 正常运行 */
    NORMAL("正常"),

    /** 正常运行（别名，兼容不同命名约定） */
    ACTIVE("正常"),

    /** 尚未激活 */
    NOT_ACTIVATED("未激活"),

    /** 授权已过期 */
    EXPIRED("已过期"),

    /** 离线宽限期：允许查看，禁止核心操作 */
    OFFLINE_GRACE("离线宽限期"),

    /** 离线锁死：仅只读浏览，需联系运维恢复 */
    OFFLINE_LOCK("离线锁死"),

    /** 锁死（别名） */
    LOCKED("已锁定"),

    /** 受限模式：功能可用但算薪错误 */
    RESTRICTED("受限模式"),

    /** 指纹不匹配 */
    FINGERPRINT_MISMATCH("指纹不匹配"),

    /** 版本不允许 */
    VERSION_NOT_ALLOWED("版本不允许");

    private final String description;

    LicenseStatusEnum(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }
}
