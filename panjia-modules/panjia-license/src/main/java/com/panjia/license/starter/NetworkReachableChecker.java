package com.panjia.license.starter;

import com.panjia.license.config.LicenseProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * networkReachable 判定器。
 *
 * ⚠️ 铁律 10：严格按以下语义定义，5xx ≠ 断网。
 *
 * networkReachable 的精确语义只有一个：判断"授权服务器网络路径能否建立 TCP 连接"。
 * - DNS 失败（UnknownHostException）→ false（真·断网）
 * - TCP 连接被拒/超时（ConnectException/SocketTimeoutException）→ true（IP 可达、端口无响应，仍算 reachable）
 * - TCP 通但 HTTP 5xx → true（服务端应用层故障，非网络层）
 *
 * 反例锁死条件：任何把"服务端 5xx / TCP 端口不可达"判成 false 的实现 = Bug。
 */
@Slf4j
@Component
public class NetworkReachableChecker {

    private final LicenseProperties properties;
    private volatile boolean cachedReachable = true;
    private volatile long lastCheckTime = 0;
    private static final long CACHE_TTL_MS = 30_000L; // 30s 缓存，避免频繁建连

    public NetworkReachableChecker(LicenseProperties properties) {
        this.properties = properties;
    }

    /**
     * 判定授权服务器网络路径是否可建立 TCP 连接。
     * @return true = TCP 可建立（含服务端应用层故障）；false = 真·断网（DNS 失败）
     */
    public boolean isNetworkReachable() {
        long now = System.currentTimeMillis();
        if (cachedReachable && now - lastCheckTime < CACHE_TTL_MS) {
            return cachedReachable;
        }
        boolean result = doCheck();
        cachedReachable = result;
        lastCheckTime = now;
        log.debug("[NetworkReachableChecker] 判定结果: {}", result);
        return result;
    }

    /**
     * 强制刷新缓存。
     */
    public void refresh() {
        lastCheckTime = 0;
    }

    /**
     * 实际 TCP 连接探测。
     * 仅"TCP 连接无法建立"为 false，其余均为 true。
     */
    private boolean doCheck() {
        String host = extractHost(properties.getServerUrl());
        int port = 443;
        try {
            InetAddress addr = InetAddress.getByName(host);
            Socket sock = new Socket();
            sock.connect(new InetSocketAddress(addr, port), properties.getTcpTimeoutMs());
            sock.close();
            return true;
        } catch (java.net.UnknownHostException e) {
            // DNS 解析失败 → TCP 无法建立 → 真·断网
            log.warn("[NetworkReachableChecker] DNS 解析失败，判定断网: {}", e.getMessage());
            return false;
        } catch (java.net.ConnectException e) {
            // TCP 连接被拒/超时（端口无响应）
            // → 服务器 IP 可达、端口不可达，网络路径仍建立 → reachable = true
            log.debug("[NetworkReachableChecker] TCP 连接被拒（端口无响应），判定 reachable=true（服务端不可达）");
            return true;
        } catch (java.net.SocketTimeoutException e) {
            // connect() 超时 → 同上，服务端不可达 → reachable = true
            log.debug("[NetworkReachableChecker] TCP 连接超时，判定 reachable=true（服务端不可达）");
            return true;
        } catch (Exception e) {
            // 其他异常（IOException 等）→ 视为网络不可达 → reachable = false
            log.warn("[NetworkReachableChecker] 网络异常: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 从 URL 提取主机名。
     */
    private String extractHost(String url) {
        try {
            return java.net.URI.create(url).getHost();
        } catch (Exception e) {
            return url.replaceAll("https?://", "").split("/")[0];
        }
    }
}
