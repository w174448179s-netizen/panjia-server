package com.panjia.license.service;

import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.panjia.license.LicenseMode;
import com.panjia.license.config.LicenseProperties;
import com.panjia.license.crypto.verify.KeyStore;
import com.panjia.license.crypto.verify.LicenseVerifier;
import com.panjia.license.domain.HardwareFingerprint;
import com.panjia.license.domain.LicenseContent;
import com.panjia.license.enums.ClientModeEnum;
import com.panjia.license.enums.CheckResultEnum;
import com.panjia.license.enums.LicenseStatusEnum;
import com.panjia.license.enums.OperationEnum;
import com.panjia.license.exception.LicenseException;
import com.panjia.license.starter.NetworkReachableChecker;
import com.panjia.license.util.LicenseFileUtils;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.OffsetDateTime;

/**
 * LicenseService 实现。
 * 调用授权服务器接口，管理激活/心跳/校验全流程。
 *
 * dev 模式下使用预置 dev token 初始化，跳过远程调用。
 */
@Slf4j
@Service
public class LicenseServiceImpl implements LicenseService {

    private final LicenseProperties properties;
    private final LicenseContext context;
    private final LicenseVerifier licenseVerifier;
    private final LicenseFileUtils fileUtils;
    private final FingerprintService fingerprintService;
    private final KeyStore keyStore;
    private final NetworkReachableChecker networkReachableChecker;

    public LicenseServiceImpl(LicenseProperties properties, LicenseContext context,
                              LicenseVerifier licenseVerifier, LicenseFileUtils fileUtils,
                              FingerprintService fingerprintService, KeyStore keyStore,
                              NetworkReachableChecker networkReachableChecker) {
        this.properties = properties;
        this.context = context;
        this.licenseVerifier = licenseVerifier;
        this.fileUtils = fileUtils;
        this.fingerprintService = fingerprintService;
        this.keyStore = keyStore;
        this.networkReachableChecker = networkReachableChecker;
    }

    /**
     * 启动初始化：优先从磁盘加载已持久化的 token，其次使用 dev token。
     * 所有模式均执行磁盘加载（dev/test/prod），确保重启后自动恢复。
     */
    @PostConstruct
    public void initOnStartup() {
        // 1. 所有模式：尝试从磁盘加载已持久化的 token
        String persistedToken = fileUtils.readFirstLine(properties.getFile().getToken());
        if (persistedToken != null && !persistedToken.isEmpty()) {
            try {
                LicenseContent content = licenseVerifier.decodeToken(persistedToken);
                verifyFingerprint(content); // L2 机器指纹比对：防止容器整体拷贝
                context.setLicense(content, persistedToken);
                context.setFingerprint(fingerprintService.getCurrentFingerprint());
                log.info("[initOnStartup] 从磁盘加载 token 成功，authCode={}", content.getAuthCode());
                return;
            } catch (Exception e) {
                log.warn("[initOnStartup] 持久化 token 已失效或指纹不匹配，清除: {}", e.getMessage());
                fileUtils.delete(properties.getFile().getToken());
            }
        }

        // 2. DEV 模式：使用 dev token（prod 构建时编译器消除此分支）
        if (LicenseMode.DEV && !properties.isTestMode() && !properties.getDevToken().isEmpty()) {
            try {
                LicenseContent content = licenseVerifier.decodeToken(properties.getDevToken());
                context.setLicense(content, properties.getDevToken());
                context.setNetworkReachable(true);
                context.setFingerprint(fingerprintService.getCurrentFingerprint());
                log.info("[initOnStartup] dev 模式初始化完成，authCode={}", content.getAuthCode());
            } catch (Exception e) {
                log.error("[initOnStartup] dev token 解析失败: {}", e.getMessage());
            }
            return;
        }

        // 3. authCode 自动激活（prod 或 dev+testMode，且 authCode 已配置）
        // 硬失败：激活失败直接抛异常阻止 Spring 上下文刷新（@PostConstruct 抛异常 = 启动失败）
        if ((!LicenseMode.DEV || properties.isTestMode()) && !properties.getAuthCode().isEmpty()) {
            try {
                log.info("[initOnStartup] 检测到 authCode，开始自动激活");
                HardwareFingerprint fp = fingerprintService.getCurrentFingerprint();
                activate(properties.getAuthCode(), fp, properties.getProductVersion());
                log.info("[initOnStartup] 自动激活成功");
            } catch (Exception e) {
                log.error("[initOnStartup] 自动激活失败，拒绝启动: {}", e.getMessage());
                throw new LicenseException("License 自动激活失败，应用拒绝启动: " + e.getMessage(), e);
            }
        }
    }

    @Override
    public ActivateResult activate(String authCode, HardwareFingerprint fingerprint, String productVersion) {
        log.info("[activate] 开始激活，authCode={}, fpHash={}", maskAuthCode(authCode), fingerprint.calculateHash());

        String url = properties.getServerUrl() + "/api/auth/activate";
        JSONObject body = new JSONObject();
        body.set("authCode", authCode);
        body.set("fingerprint", fingerprint.calculateHash());
        body.set("productVersion", productVersion);
        body.set("instanceId", fingerprint.getInstanceId());

        HttpResponse resp = HttpRequest.post(url)
                .body(body.toString())
                .timeout(properties.getTcpTimeoutMs())
                .setSSLSocketFactory(keyStore.getSSLSocketFactory())
                .execute();

        if (!resp.isOk()) {
            log.warn("[activate] 授权服务器返回非 2xx: {}", resp.getStatus());
            throw new LicenseException("激活失败：授权服务器返回 " + resp.getStatus());
        }

        JSONObject json = JSONUtil.toBean(resp.body(), JSONObject.class);
        String token = json.getStr("jwt");
        long offlineExpireAt = json.getStr("offlineExpireAt") != null
                ? OffsetDateTime.parse(json.getStr("offlineExpireAt")).toInstant().toEpochMilli() : 0L;
        String clientMode = json.getStr("clientMode", ClientModeEnum.NORMAL.name());

        // 解码 License 内容
        LicenseContent content = licenseVerifier.decodeToken(token);
        verifyFingerprint(content); // L2 机器指纹比对：防御服务端 bug 导致指纹错配

        // 写入上下文
        context.setLicense(content, token);
        context.setFingerprint(fingerprint);
        context.invalidateCheckCache(null); // 清空所有操作缓存

        // 处理 clientMode 指令
        if (ClientModeEnum.RESTRICT.name().equals(clientMode)) {
            context.setRestricted(ClientModeEnum.RESTRICT);
            log.warn("[activate] 服务端下发受限模式指令，clientMode=RESTRICT");
        }

        // 持久化 token 到磁盘，重启后自动恢复
        try {
            fileUtils.ensureDataDir();
            fileUtils.atomicWrite(properties.getFile().getToken(), token);
            log.info("[activate] token 已持久化到磁盘");
        } catch (Exception e) {
            log.error("[activate] token 持久化失败: {}", e.getMessage());
        }

        log.info("[activate] 激活成功");
        return new ActivateResult(token, offlineExpireAt, clientMode);
    }

    @Override
    public HeartbeatResult heartbeat() {
        // dev 模式：跳过远程心跳（LicenseMode.DEV 是编译时常量，prod 构建时此分支被消除）
        if (LicenseMode.DEV && !properties.isTestMode()) {
            log.debug("[heartbeat] dev 模式，跳过远程心跳");
            return new HeartbeatResult("NORMAL", 0L, ClientModeEnum.NORMAL.name());
        }

        if (context.getToken() == null) {
            throw new LicenseException("未激活，无法心跳");
        }

        if (context.getFingerprint() == null) {
            throw new LicenseException("指纹未初始化，无法心跳");
        }

        String url = properties.getServerUrl() + "/api/auth/heartbeat";

        JSONObject body = new JSONObject();
        body.set("instanceId", context.getFingerprint().getInstanceId());
        body.set("fingerprint", context.getFingerprint().calculateHash());
        body.set("reportedAt", OffsetDateTime.now().toString());

        HttpResponse resp;
        try {
            resp = HttpRequest.post(url)
                    .header("Authorization", "Bearer " + context.getToken())
                    .body(body.toString())
                    .timeout(properties.getTcpTimeoutMs())
                    .setSSLSocketFactory(keyStore.getSSLSocketFactory())
                    .execute();
        } catch (Exception e) {
            // 连接异常（超时/被拒/网络不通）→ 心跳失败处理
            return handleHeartbeatFailure(e.getMessage());
        }

        if (!resp.isOk()) {
            return handleHeartbeatFailure("HTTP " + resp.getStatus());
        }

        // ---------- 心跳成功 ----------
        JSONObject json = JSONUtil.toBean(resp.body(), JSONObject.class);
        String clientMode = json.getStr("clientMode", ClientModeEnum.NORMAL.name());
        long offlineExpireAt = json.getStr("offlineExpireAt") != null
                ? OffsetDateTime.parse(json.getStr("offlineExpireAt")).toInstant().toEpochMilli() : 0L;
        String newToken = json.getStr("token", json.getStr("newToken"));

        if (context.getLicenseContent() != null) {
            context.updateOfflineExpireAt(Instant.ofEpochMilli(offlineExpireAt));
        }
        context.setLastHeartbeatTime(System.currentTimeMillis());

        // 缺陷1修复：心跳成功 → 网络可达，重置失败计数，从离线宽限期恢复
        context.setNetworkReachable(true);
        context.resetHeartbeatFailure();
        context.clearServerUnavailable(); // 心跳成功说明服务器恢复，清除 check 短路标记
        if (context.getStatus() == LicenseStatusEnum.OFFLINE_GRACE) {
            context.setRestricted(ClientModeEnum.NORMAL); // setRestricted(NORMAL) 会把 status 置回 NORMAL
            log.info("[heartbeat] 从离线宽限期恢复为正常模式");
        }

        // 处理 clientMode
        if (ClientModeEnum.RESTRICT.name().equals(clientMode)) {
            context.setRestricted(ClientModeEnum.RESTRICT);
        } else if (ClientModeEnum.NORMAL.name().equals(clientMode)) {
            context.setRestricted(ClientModeEnum.NORMAL);
        }

        // 自动续签：服务端返回新 token 时，验签后更新内存 + 持久化磁盘，客户无感
        if (newToken != null && !newToken.isEmpty()) {
            renewToken(newToken);
        }

        log.info("[heartbeat] 心跳成功，clientMode={}, renewed={}", clientMode, newToken != null);
        return new HeartbeatResult("NORMAL", offlineExpireAt, clientMode, newToken);
    }

    /**
     * 自动续签 token：验签新 token → 更新内存 context → 持久化到磁盘。
     * 客户无需重启服务，续签无感完成。
     */
    private void renewToken(String newToken) {
        try {
            LicenseContent content = licenseVerifier.decodeToken(newToken);
            verifyFingerprint(content); // L2 机器指纹比对：续签 token 必须仍是同一台机器
            // 验签通过 → 更新内存（setLicense 会重置状态为 NORMAL、清空缓存、重置失败计数）
            context.setLicense(content, newToken);
            // 持久化到磁盘，重启后自动加载新 token
            fileUtils.ensureDataDir();
            fileUtils.atomicWrite(properties.getFile().getToken(), newToken);
            log.info("[renewToken] token 自动续签成功，新过期时间={}", content.getExpiresAt());
        } catch (Exception e) {
            // 新 token 验签失败 → 不更新，继续用旧 token（安全降级）
            log.error("[renewToken] 新 token 验签失败，续签取消，继续使用旧 token: {}", e.getMessage());
        }
    }

    /**
     * 心跳失败统一处理：回写网络可达状态、递增失败计数。
     * 设计原则：服务器挂了不影响客户操作，只在 token 过期时才锁死。
     */
    private HeartbeatResult handleHeartbeatFailure(String reason) {
        // 缺陷1修复：心跳失败时探测网络可达性并回写 context
        boolean reachable = networkReachableChecker.isNetworkReachable();
        context.setNetworkReachable(reachable);

        // 连续失败计数（用于监控/诊断，不再触发操作拒绝）
        int failures = context.incrementHeartbeatFailure();
        log.warn("[heartbeat] 心跳失败（第 {} 次），networkReachable={}，原因={}", failures, reachable, reason);

        // 只有 token 过期才进入离线锁死，服务器挂了不锁死
        if (isTokenExpired() && context.getStatus() != LicenseStatusEnum.OFFLINE_LOCK) {
            log.error("[heartbeat] token 已过期，进入离线锁死");
            context.setOfflineLock();
            return new HeartbeatResult("FAILED", 0L, ClientModeEnum.NORMAL.name());
        }

        // 连续失败达到阈值 → 进入离线宽限期（仅状态标记，不拒绝操作）
        if (failures >= properties.getHeartbeatFailureGraceThreshold()
                && context.getStatus() == LicenseStatusEnum.NORMAL) {
            log.warn("[heartbeat] 连续心跳失败 {} 次，进入离线宽限期（不影响操作）", failures);
            context.setOfflineGrace();
        }

        return new HeartbeatResult("FAILED", 0L, ClientModeEnum.NORMAL.name());
    }

    @Override
    public CheckResult check(String operation) {
        OperationEnum op = OperationEnum.valueOf(operation);

        // dev 模式：直接放行所有操作（LicenseMode.DEV 是编译时常量，prod 构建时此分支被消除）
        if (LicenseMode.DEV && !properties.isTestMode()) {
            return new CheckResult(true, "dev 模式，操作允许", CheckResultEnum.ALLOWED.getCode());
        }

        // 1. token 过期检查（运行中过期）→ 拒绝
        // token 是 JWT + RSA 签名，本地可验签。过期说明授权到期，必须连服务器续签。
        if (isTokenExpired()) {
            log.error("[check] token 已过期，操作被拒: {}", operation);
            if (context.getStatus() != LicenseStatusEnum.OFFLINE_LOCK) {
                context.setOfflineLock();
            }
            return new CheckResult(false, "授权已过期，请联系服务商续签", CheckResultEnum.OPERATION_DENIED.getCode());
        }

        // 1.5 L2 运行时指纹比对：防止容器运行中被迁移到其他机器
        // 采集开销极小（读两个文件），但能在运行中检测授权迁移
        if (context.getLicenseContent() != null && context.getLicenseContent().getFingerprintHash() != null) {
            try {
                String currentFpHash = fingerprintService.getCurrentFingerprint().calculateHash();
                if (!context.getLicenseContent().getFingerprintHash().equals(currentFpHash)) {
                    log.error("[check] 机器指纹不匹配，疑似授权迁移，操作被拒: {}", operation);
                    context.setRestricted(ClientModeEnum.RESTRICT);
                    return new CheckResult(false, "机器指纹不匹配，授权已锁定", CheckResultEnum.OPERATION_DENIED.getCode());
                }
            } catch (Exception e) {
                log.warn("[check] 运行时指纹采集失败: {}", e.getMessage());
            }
        }

        // 2. 受限模式（服务端明确下发的 clientMode=RESTRICT）→ 核心操作禁止
        // 注意：即使服务器不可达，restricted 状态保留，因为这是服务端的明确指令
        if (context.isRestricted()) {
            if (op == OperationEnum.EXPORT) {
                // 受限模式下允许导出核对（不阻止客户自查）
                return new CheckResult(true, "受限模式，导出允许", "ALLOWED");
            }
            return new CheckResult(false, "授权受限，核心功能不可用，请联系服务商", CheckResultEnum.OPERATION_DENIED.getCode());
        }

        // 3. 服务器不可用短路期内 → 信任本地 token 放行，不发 HTTP 请求
        if (context.isServerUnavailable()) {
            log.debug("[check] 服务器不可用短路期内，信任本地 token 放行，操作={}", operation);
            return new CheckResult(true, "服务器不可达，信任本地授权", CheckResultEnum.CACHE_FALLBACK.getCode());
        }

        // 4. 服务器可达 → 调用 /check 实时校验
        return doRemoteCheck(op);
    }

    /**
     * 判断本地 token 是否已过期。
     * 优先用 JWT 的 expiresAt，其次用 licenseExpireAt。
     */
    @Override
    public boolean isTokenExpired() {
        LicenseContent content = context.getLicenseContent();
        if (content == null) {
            return true;
        }
        long now = System.currentTimeMillis();
        if (content.getExpiresAt() != null && content.getExpiresAt().toEpochMilli() < now) {
            return true;
        }
        if (content.getLicenseExpireAt() != null && content.getLicenseExpireAt().toEpochMilli() < now) {
            return true;
        }
        return false;
    }

    /**
     * 调用服务端 /check 接口。
     */
    private CheckResult doRemoteCheck(OperationEnum op) {
        String url = properties.getServerUrl() + "/api/auth/check";
        JSONObject body = new JSONObject();
        body.set("productVersion", properties.getProductVersion());

        HttpResponse resp;
        try {
            resp = HttpRequest.post(url)
                    .header("Authorization", "Bearer " + context.getToken())
                    .body(body.toString())
                    .timeout(properties.getTcpTimeoutMs())
                    .setSSLSocketFactory(keyStore.getSSLSocketFactory())
                    .execute();
        } catch (Exception e) {
            // 连接异常（超时/被拒/网络不通）→ 信任本地 token 放行
            // 设置短路窗口，后续 check 不再发 HTTP 请求
            context.setServerUnavailableUntil(System.currentTimeMillis() + properties.getCheckFailureBackoffMs());
            log.warn("[check] 服务端连接异常（{}），短路 {}ms，信任本地 token 放行，操作={}",
                    e.getMessage(), properties.getCheckFailureBackoffMs(), op.name());
            return new CheckResult(true, "服务器不可达，信任本地授权", CheckResultEnum.CACHE_FALLBACK.getCode());
        }

        if (resp.isOk()) {
            JSONObject json = JSONUtil.toBean(resp.body(), JSONObject.class);
            String clientMode = json.getStr("clientMode", ClientModeEnum.NORMAL.name());
            String code = json.getStr("code", "");
            boolean allowed = ClientModeEnum.NORMAL.name().equals(clientMode);
            String reason = allowed ? "操作允许" : "受限模式，操作禁止";
            // check 成功 → 清除服务器不可用短路标记（说明服务器恢复了）
            context.clearServerUnavailable();
            // 服务端下发受限模式
            if (!allowed) {
                context.setRestricted(ClientModeEnum.RESTRICT);
            }
            return new CheckResult(allowed, reason, code);
        }

        // 服务端 5xx → 信任本地 token 放行
        context.setServerUnavailableUntil(System.currentTimeMillis() + properties.getCheckFailureBackoffMs());
        log.warn("[check] 服务端不可达（5xx），短路 {}ms，信任本地 token 放行，操作={}",
                properties.getCheckFailureBackoffMs(), op.name());
        return new CheckResult(true, "服务器不可达，信任本地授权", CheckResultEnum.CACHE_FALLBACK.getCode());
    }

    @Override
    public boolean isOperationAllowed(String operation) {
        return check(operation).isAllowed();
    }

    @Override
    public LicenseStatusEnum getCurrentStatus() {
        return context.getStatus();
    }

    @Override
    public boolean isRestricted() {
        return context.isRestricted();
    }

    @Override
    public HardwareFingerprint getCurrentFingerprint() {
        return context.getFingerprint();
    }

    @Override
    public LicenseContent getLicenseContent() {
        return context.getLicenseContent();
    }

    @Override
    public String getToken() {
        return context.getToken();
    }

    /**
     * 脱敏授权码（日志输出用）。
     */
    private String maskAuthCode(String authCode) {
        if (authCode == null || authCode.length() < 8) {
            return "******";
        }
        return authCode.substring(0, 4) + "****" + authCode.substring(authCode.length() - 4);
    }

    /**
     * 机器指纹比对：验证 token 绑定的机器与当前机器一致。
     * 防止授权/容器整体拷贝到其他机器使用。
     *
     * 比对时机：加载 token 后（磁盘加载 / 激活 / 续签），setLicense 之前。
     * 不匹配则抛异常，阻止 token 生效。
     *
     * 例外：
     * - dev 模式跳过（开发环境 token 可能在不同机器生成）
     * token 无 fingerprintHash 时跳过（兼容旧版 token）
     */
    private void verifyFingerprint(LicenseContent content) {
        if (LicenseMode.DEV) {
            return;
        }
        String tokenFpHash = content.getFingerprintHash();
        if (tokenFpHash == null || tokenFpHash.isEmpty()) {
            log.warn("[verifyFingerprint] token 无指纹信息，跳过比对（兼容旧版）");
            return;
        }
        String currentFpHash = fingerprintService.getCurrentFingerprint().calculateHash();
        if (!tokenFpHash.equals(currentFpHash)) {
            throw new LicenseException(
                    "机器指纹不匹配：token 绑定机器=" + tokenFpHash + "，当前机器=" + currentFpHash
                            + "（疑似授权迁移到其他机器）");
        }
    }
}
