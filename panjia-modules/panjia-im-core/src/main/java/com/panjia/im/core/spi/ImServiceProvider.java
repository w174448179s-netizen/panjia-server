package com.panjia.im.core.spi;

/**
 * IM 服务提供者 SPI 接口
 * <p>
 * 钉钉、企微、飞书适配器需实现此接口。
 */
public interface ImServiceProvider {

    /**
     * 发送消息
     */
    void sendMessage(String to, String content);

    /**
     * 适配器名称
     */
    String getProviderName();
}
