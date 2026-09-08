# ============================================================
# panjia-license ProGuard 混淆规则
#
# 策略：保守混淆，不破坏 Spring 反射和 IntegrityChecker hash 校验
# - keep 所有类名（Spring @ComponentScan 需要）
# - keep 所有接口
# - keep 所有注解
# - keep 所有 public 方法和构造函数（Spring 注入 + 外部 API 调用）
# - keep @ConfigurationProperties 类的字段名（配置绑定）
# - keep LicenseMode（编译时常量，混淆无意义）
# - 混淆 private 方法、字段、内部类
# ============================================================

# 输入/输出：排除 tools 包，输出到临时目录（antrun 再复制回 target/classes）
-injars target/classes(!com/panjia/license/tools/**)
-outjars target/classes-proguard

# 保留所有注解
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod

# 强制 Java 21 目标版本，修复 stackmap frame 问题
-target 21

# 保留所有接口
-keep public interface com.panjia.license.** { *; }

# 保留所有类名（Spring 组件扫描需要），但允许混淆成员
-keep class com.panjia.license.**

# 保留所有 public 方法和构造函数
-keepclassmembers class com.panjia.license.** {
    public <init>(...);
    public *** *(...);
}

# 保留 @ConfigurationProperties 类的所有方法（Lombok @Data 生成的 equals/hashCode/toString 混淆易出 VerifyError）
-keep class com.panjia.license.config.** { *; }

# 保留 LicenseMode（编译时常量，混淆无意义且 IntegrityChecker 校验）
-keep class com.panjia.license.LicenseMode { *; }

# 保留所有枚举（枚举值不能被混淆）
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# 保留 Lombok 生成的方法（@Getter/@Setter/@Builder）
-keep class lombok.** { *; }
-dontwarn lombok.**

# 保留 Spring 相关
-keep class org.springframework.** { *; }
-dontwarn org.springframework.**

# 保留 JWT 相关（反射调用）
-keep class io.jsonwebtoken.** { *; }
-dontwarn io.jsonwebtoken.**

# 保留 Hutool 相关
-keep class cn.hutool.** { *; }
-dontwarn cn.hutool.**

# 不警告缺失的依赖
-dontwarn javax.**
-dontwarn java.**

# 不优化（避免优化破坏反射）
-dontoptimize

# 预验证生成 StackMapTable，Java 7+ 必须，否则 JVM 类加载 VerifyError

# 混淆时保留源文件和行号（方便排查问题，安全与可维护性权衡）
-keepattributes SourceFile,LineNumberTable

# 不混淆泛型签名（Jackson 反序列化需要）
-keepattributes Signature

# 保留所有异常类
-keep class com.panjia.license.exception.** { *; }

# 保留 domain 实体类（LicenseContent、HardwareFingerprint 等）
-keep class com.panjia.license.domain.** { *; }
