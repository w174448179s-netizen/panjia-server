package com.panjia.license.service;

import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.panjia.license.LicenseMode;
import com.panjia.license.config.LicenseProperties;
import com.panjia.license.crypto.verify.LicenseVerifier;
import com.panjia.license.domain.HardwareFingerprint;
import com.panjia.license.domain.LicenseContent;
import com.panjia.license.enums.ClientModeEnum;
import com.panjia.license.enums.CheckResultEnum;
import com.panjia.license.enums.LicenseStatusEnum;
import com.panjia.license.enums.OperationEnum;
import com.panjia.license.exception.LicenseException;
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

    public LicenseServiceImpl(LicenseProperties properties, LicenseContext context,
                              LicenseVerifier licenseVerifier, LicenseFileUtils fileUtils,
                              FingerprintService fingerprintService) {
        this.properties = properties;
        this.context = context;
        this.licenseVerifier = licenseVerifier;
        this.fileUtils = fileUtils;
        this.fingerprintService = fingerprintService;
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
                context.setLicense(content, persistedToken);
                log.info("[initOnStartup] 从磁盘加载 token 成功，authCode={}", content.getAuthCode());
                return;
            } catch (Exception e) {
                log.warn("[initOnStartup] 持久化 token 已失效，清除: {}", e.getMessage());
                fileUtils.delete(properties.getFile().getToken());
            }
        }

        // 2. DEV 模式：使用 dev token（prod 构建时编译器消除此分支）
        if (LicenseMode.DEV && !properties.isTestMode() && !properties.getDevToken().isEmpty()) {
            try {
                LicenseContent content = licenseVerifier.decodeToken(properties.getDevToken());
                context.setLicense(content, properties.getDevToken());
                context.setNetworkReachable(true);
                log.info("[initOnStartup] dev 模式初始化完成，authCode={}", content.getAuthCode());
            } catch (Exception e) {
                log.error("[initOnStartup] dev token 解析失败: {}", e.getMessage());
            }
            return;
        }

        // 3. authCode 自动激活（prod 或 dev+testMode，且 authCode 已配置）
        if ((!LicenseMode.DEV || properties.isTestMode()) && !properties.getAuthCode().isEmpty()) {
            try {
                log.info("[initOnStartup] 检测到 authCode，开始自动激活");
                HardwareFingerprint fp = fingerprintService.getCurrentFingerprint();
                activate(properties.getAuthCode(), fp, properties.getProductVersion());
                log.info("[initOnStartup] 自动激活成功");
            } catch (Exception e) {
                log.error("[initOnStartup] 自动激活失败: {}", e.getMessage());
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
                .execute();

        if (!resp.isOk()) {
            log.warn("[activate] 授权服务器返回非 2xx: {}", resp.getStatus());
            throw new LicenseException("激活失败：授权服务器返回 " + resp.getStatus());
        }

        JSONObject json = JSONUtil.toBean(resp.body(), JSONObject.class);
        String token = json.getStr("jwt");
        String offlineExpireAtStr = json.getStr("offlineExpireAt");
        long offlineExpireAt = offlineExpireAtStr != null
                ? OffsetDateTime.parse(offlineExpireAtStr).toInstant().toEpochMilli() : 0L;
        String clientMode = json.getStr("clientMode", ClientModeEnum.NORMAL.name());

        // 解码 License 内容
        LicenseContent content = licenseVerifier.decodeToken(token);

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

        String url = properties.getServerUrl() + "/api/auth/heartbeat";

        JSONObject body = new JSONObject();
        body.set("instanceId", context.getFingerprint().getInstanceId());
        body.set("fingerprint", context.getFingerprint().calculateHash());
        body.set("reportedAt", OffsetDateTime.now().toString());

        HttpResponse resp = HttpRequest.post(url)
                .header("Authorization", "Bearer " + context.getToken())
                .body(body.toString())
                .timeout(properties.getTcpTimeoutMs())
                .execute();

        if (!resp.isOk()) {
            log.warn("[heartbeat] 心跳失败，status={}", resp.getStatus());
            return new HeartbeatResult("FAILED", 0L, ClientModeEnum.NORMAL.name());
        }

        JSONObject json = JSONUtil.toBean(resp.body(), JSONObject.class);
        String clientMode = json.getStr("clientMode", ClientModeEnum.NORMAL.name());
        String offlineExpireAtStr = json.getStr("offlineExpireAt");
        long offlineExpireAt = offlineExpireAtStr != null
                ? OffsetDateTime.parse(offlineExpireAtStr).toInstant().toEpochMilli() : 0L;

        // 铁律 5：offlineExpireAt 仅在心跳成功时刷新
        if (context.getLicenseContent() != null) {
            context.getLicenseContent().setOfflineExpireAt(Instant.ofEpochMilli(offlineExpireAt));
        }
        context.setLastHeartbeatTime(System.currentTimeMillis());

        // 处理 clientMode
        if (ClientModeEnum.RESTRICT.name().equals(clientMode)) {
            context.setRestricted(ClientModeEnum.RESTRICT);
        } else if (ClientModeEnum.NORMAL.name().equals(clientMode)) {
            context.setRestricted(ClientModeEnum.NORMAL);
        }

        log.info("[heartbeat] 心跳成功，clientMode={}", clientMode);
        return new HeartbeatResult("NORMAL", offlineExpireAt, clientMode);
    }

    @Override
    public CheckResult check(String operation) {
        OperationEnum op = OperationEnum.valueOf(operation);

        // dev 模式：直接放行所有操作（LicenseMode.DEV 是编译时常量，prod 构建时此分支被消除）
        if (LicenseMode.DEV && !properties.isTestMode()) {
            return new CheckResult(true, "dev 模式，操作允许", CheckResultEnum.ALLOWED.getCode());
        }

        // 1. 真·断网 → 离线模式禁用 /check 缓存（铁律 2）
        if (!context.isNetworkReachable()) {
            log.warn("[check] 网络不可达，离线模式禁用缓存，操作={}", operation);
            return new CheckResult(false, CheckResultEnum.NETWORK_OFFLINE.getMessage(), CheckResultEnum.NETWORK_OFFLINE.getCode());
        }

        // 2. 离线锁死 → 禁止所有核心操作
        if (context.getStatus() == LicenseStatusEnum.OFFLINE_LOCK) {
            return new CheckResult(false, "离线锁死，功能受限", CheckResultEnum.OPERATION_DENIED.getCode());
        }

        // 3. 受限模式 → 核心操作禁止（算薪/导入/导出均受限）
        if (context.isRestricted()) {
            if (op == OperationEnum.EXPORT) {
                // 受限模式下允许导出核对（不阻止客户自查）
                return new CheckResult(true, "受限模式，导出允许", "ALLOWED");
            }
            return new CheckResult(false, "受限模式，核心操作禁止", CheckResultEnum.OPERATION_DENIED.getCode());
        }

        // 4. 离线宽限期 → 禁止核心操作，仅允许查看
        if (context.getStatus() == LicenseStatusEnum.OFFLINE_GRACE) {
            if (op == OperationEnum.EXPORT) {
                return new CheckResult(true, "离线宽限期，导出允许", "ALLOWED");
            }
            return new CheckResult(false, "离线宽限期，核心操作禁止", CheckResultEnum.OPERATION_DENIED.getCode());
        }

        // 5. 在线模式 → 调用 /check 实时校验
        return doRemoteCheck(op);
    }

    /**
     * 调用服务端 /check 接口。
     */
    private CheckResult doRemoteCheck(OperationEnum op) {
        String url = properties.getServerUrl() + "/api/auth/check";
        JSONObject body = new JSONObject();
        body.set("productVersion", properties.getProductVersion());

        HttpResponse resp = HttpRequest.post(url)
                .header("Authorization", "Bearer " + context.getToken())
                .body(body.toString())
                .timeout(properties.getTcpTimeoutMs())
                .execute();

        if (resp.isOk()) {
            JSONObject json = JSONUtil.toBean(resp.body(), JSONObject.class);
            String clientMode = json.getStr("clientMode", ClientModeEnum.NORMAL.name());
            String code = json.getStr("code", "");
            boolean allowed = ClientModeEnum.NORMAL.name().equals(clientMode);
            String reason = allowed ? "操作允许" : "受限模式，操作禁止";
            // 写入缓存
            if (allowed) {
                context.cacheCheckResult(op.name(), properties.getCheckCacheTtlMs());
            }
            return new CheckResult(allowed, reason, code);
        }

        // 服务端暂时不可达 → 走 30 分钟缓存降级（铁律 10：5xx ≠ 断网）
        log.warn("[check] 服务端不可达（5xx/超时），走缓存降级，操作={}", op.name());
        if (context.isCheckCacheValid(op.name())) {
            return new CheckResult(true, CheckResultEnum.CACHE_FALLBACK.getMessage(), CheckResultEnum.CACHE_FALLBACK.getCode());
        }
        return new CheckResult(false, "服务端不可达且缓存失效", CheckResultEnum.NETWORK_OFFLINE.getCode());
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

    /**
     * 脱敏授权码（日志输出用）。
     */
    private String maskAuthCode(String authCode) {
        if (authCode == null || authCode.length() < 8) {
            return "******";
        }
        return authCode.substring(0, 4) + "****" + authCode.substring(authCode.length() - 4);
    }
}
