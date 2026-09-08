package com.panjia.common.port;

import org.dromara.common.core.exception.ServiceException;

/**
 * License 校验端口（横切能力 Port）。
 * <p>
 * 业务代码只依赖此接口，不耦合具体 License 客户端实现。
 * <p>
 * 状态：✅ 已实现——已有 panjia-license 客户端直接接入，下游勿重复开发。
 */
public interface LicensePort {

    /**
     * 判断 License 是否处于激活/有效状态。
     *
     * @return true 表示当前 License 有效
     */
    default boolean isActive() {
        return false;
    }

    /**
     * 主动触发 License 校验，失败抛 ServiceException。
     *
     * @throws ServiceException 校验未通过时抛出
     */
    default void check() throws ServiceException {

    }
}
