package com.panjia.license.enums;

/**
 * 客户端运行时授权状态枚举。
 *
 * ★ P2-1 状态机语义说明（两端维度差异 + 桥接表）：
 *
 * | 本枚举（运行态）   | 控制台 AuthCodeStatus（授权态） | 语义                  |
 * |--------------------|-------------------------------|----------------------|
 * | NORMAL             | ACTIVE                        | 正常激活且心跳可达     |
 * | OFFLINE_GRACE      | ACTIVE                        | 连续心跳失败进入宽限   |
 * | OFFLINE_LOCK       | ACTIVE / EXPIRED              | token 过期或锁死       |
 * | RESTRICTED         | ACTIVE                        | 服务端下发的受限指令   |
 * | FINGERPRINT_MISMATCH| ACTIVE                       | 运行时指纹变动         |
 * | EXPIRED            | EXPIRED                       | 授权码到 endDate       |
 * | NOT_ACTIVATED      | ACTIVE                        | 客户尚未首次激活       |
 * | LOCKED             | REVOKED                       | 黑名单命中             |
 * | VERSION_NOT_ALLOWED| ACTIVE                        | productVersion 超范围 |
 *
 * 设计原则（不再强行合并两个枚举）：
 * - 客户端是"运行态"维度（动态切换：心跳、过期、模式指令）
 * - 控制台是"授权态"维度（持久状态：授权码生命周期）
 * 两个维度语义不同，强行合并会让激活流程的状态机变得不可读。
 * 上方映射表作为"语义桥接"参考，所有需要"两端状态对照"的代码请用本注释。
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
