package com.panjia.common.port;

/**
 * 备份原因枚举。
 * <p>
 * 限定备份触发场景，仅以下两种：
 * <ul>
 *   <li>{@link #BEFORE_CALCULATION} 算薪启动前</li>
 *   <li>{@link #BEFORE_UPGRADE} 系统升级前</li>
 * </ul>
 */
public enum BackupReason {

    /** 算薪启动前备份 */
    BEFORE_CALCULATION,

    /** 系统升级前备份 */
    BEFORE_UPGRADE
}
