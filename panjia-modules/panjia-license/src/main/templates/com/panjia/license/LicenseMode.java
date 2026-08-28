package com.panjia.license;

/**
 * License 模式常量（构建时由 Maven 注入）。
 *
 * 此文件由 templating-maven-plugin 从 src/main/templates 生成到 target/generated-sources。
 * 构建时 ${license.dev} 被替换为 true（dev）或 false（prod）。
 *
 * 生产构建（mvn package）：DEV=false
 *   → 编译器对所有 if (LicenseMode.DEV) 分支做死代码消除
 *   → dev 相关逻辑不存在于字节码中
 *   → 反编译看到的只有 false，没有运行时方法可改
 *
 * 开发构建（mvn -P dev package）：DEV=true
 *   → dev 分支保留，正常工作
 */
public final class LicenseMode {

    private LicenseMode() {
    }

    public static final boolean DEV = ${license.dev};
}
