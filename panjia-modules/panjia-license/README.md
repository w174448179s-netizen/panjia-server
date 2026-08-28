# panjia-license

盘家智管 License 客户端校验模块（L1-L5 防护体系）。

## 包结构

```
com.panjia.license
├── config/           # LicenseProperties、Web 配置
├── domain/           # LicenseContent、HardwareFingerprint、FingerprintFactor、VersionRange
├── enums/            # LicenseStatusEnum、FingerprintStatusEnum、ClientModeEnum、OperationEnum、TriggerCodeEnum、CheckResultEnum
├── exception/        # LicenseException 及子类（含全局处理器）
├── crypto/verify/    # LicenseVerifier（JWT 签名验签）、KeyStore（双指纹 SSL Pinning）
├── fingerprint/      # FingerprintCollector、DockerCollector
├── starter/          # LicenseStartupValidator（启动编排）、MonotonicClock（单调时钟）、NetworkReachableChecker
├── security/         # IntegrityChecker（L5）、RestrictedMode（L4）、SalaryCheckGateway
├── interceptor/      # LicenseInterceptor（关键操作 /check，按 operation 枚举）、LicenseWebConfig
├── controller/       # LicenseController（客户端调用授权服务器）
├── service/          # LicenseContext、LicenseService（接口+实现）、MultiInstanceDetector
├── diagnose/         # LicenseDiagnosticCli（仅本地控制台，禁止 HTTP 入口）
└── util/            # LicenseFileUtils、MonotonicTolerance（⚠️ 单一常量，全代码一处引用）
```

## 依赖铁律

- 禁止引入 `org.dromara`、`com.baomidou`（MyBatis-Plus）等外部框架直接依赖
- 所有外部调用通过 `panjia-common` 适配
- 返回统一 `R<T>`（ruoyi-common-core）

## 构建顺序（铁律 11）

```
mvn package
  → ProGuard          （混淆）
  → checksum-gen       （⚠️ 对混淆后的 class 算 SHA256，生成 panjia-checksums）  ← 顺序关键
  → ClassFinal         （加密 class 字节码）
```

先加密后算 hash = 运行时解密后字节码变化 = 启动即全受限（硬 bug）。

## CLI 使用

容器内本地控制台执行：

```bash
# 重建单调时钟基准（三检合一：指纹+授权+服务端在线确认）
java -jar panjia-app.jar --license-diagnose --rebuild-monotonic

# 查看状态
java -jar panjia-app.jar --license-diagnose --status

# 清除受限标记
java -jar panjia-app.jar --license-diagnose --clear-restricted
```

⚠️ CLI 禁止通过 HTTP/API 调用，仅容器本地控制台 + mTLS 通道。
