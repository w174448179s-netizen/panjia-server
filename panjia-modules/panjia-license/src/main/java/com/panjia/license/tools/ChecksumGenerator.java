package com.panjia.license.tools;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * 构建时校验和生成器。
 *
 * 在 mvn package 阶段执行，扫描 target/classes 下的关键 .class 文件，
 * 计算 SHA-256 并写入 META-INF/panjia-checksums.txt。
 * 该文件被打包进 JAR，运行时由 IntegrityChecker 读取。
 *
 * 使用方式（POM 中配置 exec-maven-plugin）：
 *   <plugin>
 *     <groupId>org.codehaus.mojo</groupId>
 *     <artifactId>exec-maven-plugin</artifactId>
 *     <executions>
 *       <execution>
 *         <phase>package</phase>
 *         <goals><goal>java</goal></goals>
 *         <configuration>
 *           <mainClass>com.panjia.license.tools.ChecksumGenerator</mainClass>
 *           <arguments>
 *             <argument>${project.build.outputDirectory}</argument>
 *           </arguments>
 *         </configuration>
 *       </execution>
 *     </executions>
 *   </plugin>
 *
 * 也可命令行直接运行：
 *   java -cp panjia-license.jar com.panjia.license.tools.ChecksumGenerator target/classes
 */
public class ChecksumGenerator {

    /** 需校验的关键 class（与 IntegrityChecker.registerCriticalClasses 保持一致） */
    private static final String[] CRITICAL_CLASSES = {
            "com/panjia/license/crypto/verify/LicenseVerifier.class",
            "com/panjia/license/crypto/verify/KeyStore.class",
            "com/panjia/license/crypto/verify/PinnedTrustManager.class",
            "com/panjia/license/security/LicenseGuard.class",
            "com/panjia/license/security/IntegrityChecker.class",
            "com/panjia/license/security/RestrictedMode.class",
            "com/panjia/license/security/SalaryCheckGateway.class",
            "com/panjia/license/security/LicenseCheckPoint.class",
            "com/panjia/license/fingerprint/DockerCollector.class",
            "com/panjia/license/starter/MonotonicClock.class",
            "com/panjia/license/starter/NetworkReachableChecker.class",
            "com/panjia/license/starter/IntegrityCheckScheduler.class",
            "com/panjia/license/service/LicenseServiceImpl.class",
            "com/panjia/license/service/LicenseContext.class",
            "com/panjia/license/interceptor/LicenseInterceptor.class",
            "com/panjia/license/starter/LicenseStartupValidator.class",
    };

    /** 输出文件路径（相对于 classes 目录） */
    private static final String OUTPUT_PATH = "META-INF/panjia-checksums.txt";

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("用法: ChecksumGenerator <classesDirectory>");
            System.exit(1);
        }

        Path classesDir = Paths.get(args[0]);
        if (!Files.isDirectory(classesDir)) {
            System.err.println("目录不存在: " + classesDir);
            System.exit(1);
        }

        System.out.println("[ChecksumGenerator] 生成校验和...");
        System.out.println("[ChecksumGenerator] classesDir = " + classesDir);

        List<String> lines = new ArrayList<>();
        lines.add("# panjia-license checksums (auto-generated, DO NOT EDIT)");
        lines.add("# generated at: " + java.time.Instant.now());
        lines.add("# class-path SHA-256");

        int count = 0;
        for (String classPath : CRITICAL_CLASSES) {
            Path classFile = classesDir.resolve(classPath);
            if (!Files.exists(classFile)) {
                System.err.println("[ChecksumGenerator] WARN: class 不存在: " + classPath);
                continue;
            }
            String hash = sha256Hex(Files.readAllBytes(classFile));
            lines.add(classPath + " " + hash);
            count++;
            System.out.println("[ChecksumGenerator]   " + classPath + " -> " + hash.substring(0, 16) + "...");
        }

        // 额外扫描：扫描整个 license 包下所有 .class
        try (Stream<Path> stream = Files.walk(classesDir.resolve("com/panjia/license"))) {
            List<Path> allClasses = new ArrayList<>();
            stream.filter(p -> p.toString().endsWith(".class"))
                    .filter(p -> !lines.stream().anyMatch(l -> l.startsWith(p.toString()
                            .replace(classesDir.toString() + "/", ""))))
                    .forEach(allClasses::add);

            for (Path classFile : allClasses) {
                String relativePath = classesDir.relativize(classFile).toString()
                        .replace("\\", "/");
                // 跳过已添加的
                if (lines.stream().anyMatch(l -> l.startsWith(relativePath + " "))) {
                    continue;
                }
                String hash = sha256Hex(Files.readAllBytes(classFile));
                lines.add(relativePath + " " + hash);
                count++;
                System.out.println("[ChecksumGenerator]   " + relativePath + " -> " + hash.substring(0, 16) + "...");
            }
        }

        // 写入文件
        Path outputPath = classesDir.resolve(OUTPUT_PATH);
        Files.createDirectories(outputPath.getParent());
        Files.write(outputPath, lines);
        System.out.println("[ChecksumGenerator] 写入 " + count + " 个校验和 -> " + outputPath);
        System.out.println("[ChecksumGenerator] 完成");
    }

    private static String sha256Hex(byte[] data) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] digest = md.digest(data);
        StringBuilder sb = new StringBuilder();
        for (byte b : digest) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
