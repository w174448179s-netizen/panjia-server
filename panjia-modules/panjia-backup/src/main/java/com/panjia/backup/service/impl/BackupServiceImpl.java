package com.panjia.backup.service.impl;

import com.panjia.backup.domain.vo.SysBackupFileVo;
import com.panjia.backup.service.BackupService;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.redis.utils.RedisUtils;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 数据库备份恢复服务实现。
 * <p>
 * 支持两种执行模式（{@code panjia.backup.mode}）：
 * <ul>
 *   <li>{@code docker}（默认，IDE/宿主机部署）：调用宿主机 docker CLI，在数据库容器内执行
 *       pg_dump / pg_restore：
 *       <ul>
 *         <li>备份：{@code docker exec -e PGPASSWORD=xx <容器> pg_dump -U xx -Fc --no-owner --no-privileges <库> > 文件}</li>
 *         <li>还原：{@code docker exec -i -e PGPASSWORD=xx <容器> pg_restore -U xx -d <库> --clean --if-exists ... < 文件}</li>
 *       </ul>
 *   </li>
 *   <li>{@code local}（后端自身运行在容器内、无 docker CLI 的场景，如 docker-local profile）：
 *       直接执行本机 pg_dump / pg_restore，经 TCP 连 {@code db-host:db-port}，
 *       密码通过进程环境变量 PGPASSWORD 传递。要求镜像内含 postgresql-client（版本 ≥ 服务端大版本）。
 *   </li>
 * </ul>
 * 容器/主机/库名/账号/目录/保留份数均可通过 panjia.backup.* 配置（环境变量）覆盖，
 * docker 模式默认值与 script/db/backup.sh、restore.sh 完全一致。文件名白名单校验防路径穿越。
 */
@Slf4j
@Service
public class BackupServiceImpl implements BackupService {

    /** 备份文件名白名单（防路径穿越）：字母数字下划线连字符 + .dump */
    private static final Pattern FILE_NAME_PATTERN = Pattern.compile("^[A-Za-z0-9_-]+\\.dump$");

    private static final DateTimeFormatter STAMP_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    /** pg_restore 单步超时（分钟）：大库还原可能较久，超时视为失败 */
    private static final long PROCESS_TIMEOUT_MINUTES = 30;

    @Autowired(required = false)
    private CacheManager cacheManager;

    /** 执行模式：docker=宿主机 docker exec；local=本机 pg 客户端直连 */
    @Value("${panjia.backup.mode:docker}")
    private String mode;

    @Value("${panjia.backup.container:postgres}")
    private String container;

    /** local 模式直连主机（容器内部署一般为 host.docker.internal） */
    @Value("${panjia.backup.db-host:localhost}")
    private String dbHost;

    @Value("${panjia.backup.db-port:5432}")
    private String dbPort;

    @Value("${panjia.backup.db-name:postgres}")
    private String dbName;

    @Value("${panjia.backup.db-user:root}")
    private String dbUser;

    @Value("${panjia.backup.db-pass:root}")
    private String dbPass;

    @Value("${panjia.backup.dir:./backup}")
    private String backupDir;

    @Value("${panjia.backup.keep:30}")
    private int keep;

    /** local 直连模式（后端容器内无 docker CLI）。 */
    private boolean localMode() {
        return "local".equalsIgnoreCase(mode);
    }

    @Override
    public List<SysBackupFileVo> listFiles() {
        Path dir = ensureBackupDir();
        if (!Files.isDirectory(dir)) {
            return new ArrayList<>();
        }
        List<SysBackupFileVo> list = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(p -> Files.isRegularFile(p) && p.getFileName().toString().endsWith(".dump"))
                .forEach(p -> {
                    SysBackupFileVo vo = new SysBackupFileVo();
                    vo.setFileName(p.getFileName().toString());
                    try {
                        long size = Files.size(p);
                        vo.setSizeBytes(size);
                        vo.setSizeLabel(humanSize(size));
                        vo.setBackupTime(LocalDateTime.ofInstant(Files.getLastModifiedTime(p).toInstant(),
                            ZoneId.systemDefault())
                            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
                    } catch (IOException e) {
                        log.warn("[备份恢复] 读取文件属性失败：{}", p, e);
                    }
                    list.add(vo);
                });
        } catch (IOException e) {
            throw new ServiceException("读取备份目录失败：" + e.getMessage());
        }
        list.sort(Comparator.comparing(SysBackupFileVo::getBackupTime,
            Comparator.nullsLast(Comparator.naturalOrder())).reversed());
        return list;
    }

    @Override
    public String createBackup() {
        Path dir = ensureBackupDir();
        String fileName = dbName + "_" + LocalDateTime.now().format(STAMP_FORMATTER) + ".dump";
        Path target = dir.resolve(fileName);
        // 与 script/db/backup.sh 同口径：pg_dump -Fc 自定义格式
        runProcess(buildDumpCommand(), target, null, "备份");
        cleanupOldBackups(dir);
        log.info("[备份恢复] 备份完成：{}", target);
        return fileName;
    }

    @Override
    public void restoreBackup(String fileName) {
        Path dump = requireValidFile(fileName);
        // 与 script/db/restore.sh 同口径：--clean --if-exists 先删后建整库覆盖
        runProcess(buildRestoreCommand(true), null, dump, "还原");
        log.info("[备份恢复] 还原完成：{} → {}", dump.getFileName(), dbName);
        clearCaches();
    }

    @Override
    public String restoreFromUpload(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ServiceException("上传文件为空，请选择有效的 .dump 备份文件");
        }
        // 原始文件名只用于后缀校验/日志/回显，绝不参与磁盘路径（防路径穿越）
        String originalName = file.getOriginalFilename();
        if (originalName == null || !originalName.toLowerCase().endsWith(".dump")) {
            throw new ServiceException("仅支持 pg_dump 自定义格式的 .dump 备份文件");
        }
        Path temp = null;
        try {
            temp = Files.createTempFile("pj-restore-", ".dump");
            file.transferTo(temp.toFile());
            // 归档完整性校验：pg_restore --list 能解析即合法备份（与 restore.sh 前置校验一致）
            runProcess(buildRestoreCommand(false), null, temp, "备份文件校验");
            // 校验通过后整库覆盖还原
            runProcess(buildRestoreCommand(true), null, temp, "还原");
            log.info("[备份恢复] 上传文件还原完成：{}（{} bytes）→ {}", originalName, file.getSize(), dbName);
            clearCaches();
            return originalName;
        } catch (IOException e) {
            throw new ServiceException("保存上传文件失败：" + e.getMessage());
        } finally {
            // 临时文件用完即删，不落备份目录
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException e) {
                    log.warn("[备份恢复] 临时文件删除失败：{}", temp, e);
                }
            }
        }
    }

    @Override
    public void deleteBackup(String fileName) {
        Path file = requireValidFile(fileName);
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            throw new ServiceException("删除备份文件失败：" + e.getMessage());
        }
        log.info("[备份恢复] 备份文件已删除：{}", fileName);
    }

    @Override
    public Path resolveBackupFile(String fileName) {
        return requireValidFile(fileName);
    }

    /**
     * 构造 pg_dump 备份命令。
     * docker 模式经 {@code docker exec} 在数据库容器内执行；local 模式本机直连 db-host:db-port。
     */
    private List<String> buildDumpCommand() {
        List<String> cmd = new ArrayList<>();
        appendExecutor(cmd, false);
        cmd.add("pg_dump");
        appendConnArgs(cmd);
        cmd.add("-Fc");
        cmd.add("--no-owner");
        cmd.add("--no-privileges");
        cmd.add(dbName);
        return cmd;
    }

    /**
     * 构造 pg_restore 命令。
     *
     * @param withDatabase true=整库覆盖还原（带 -d 库名 + --clean）；false=仅 {@code --list} 归档校验
     */
    private List<String> buildRestoreCommand(boolean withDatabase) {
        List<String> cmd = new ArrayList<>();
        appendExecutor(cmd, true);
        cmd.add("pg_restore");
        appendConnArgs(cmd);
        if (withDatabase) {
            cmd.add("-d");
            cmd.add(dbName);
            cmd.add("--clean");
            cmd.add("--if-exists");
            cmd.add("--no-owner");
            cmd.add("--no-privileges");
        } else {
            cmd.add("--list");
        }
        return cmd;
    }

    /**
     * 追加执行器前缀。
     * docker 模式：{@code docker exec [-i] -e PGPASSWORD=xx <容器>}（stdin 流式输入需 -i）；
     * local 模式：无前缀（密码稍后通过进程环境变量 PGPASSWORD 注入）。
     */
    private void appendExecutor(List<String> cmd, boolean stdin) {
        if (localMode()) {
            return;
        }
        cmd.add("docker");
        cmd.add("exec");
        if (stdin) {
            cmd.add("-i");
        }
        cmd.add("-e");
        cmd.add("PGPASSWORD=" + dbPass);
        cmd.add(container);
    }

    /** 追加连接参数：local 模式需要 -h/-p 指定 TCP 目标；docker 模式经容器内本地 socket，仅需 -U。 */
    private void appendConnArgs(List<String> cmd) {
        if (localMode()) {
            cmd.add("-h");
            cmd.add(dbHost);
            cmd.add("-p");
            cmd.add(dbPort);
        }
        cmd.add("-U");
        cmd.add(dbUser);
    }

    /**
     * 执行外部命令并等待完成。
     *
     * @param cmd        命令及参数
     * @param outputFile 输出重定向文件（备份产物），null 不重定向
     * @param inputFile  输入重定向文件（还原的 dump），null 不重定向
     * @param action     动作名（日志/异常文案）
     */
    private void runProcess(List<String> cmd, Path outputFile, Path inputFile, String action) {
        // docker 模式需确认数据库容器在运行；local 模式进程直连数据库，无 docker 依赖
        if (!localMode()) {
            checkContainerRunning();
        }
        ProcessBuilder pb = new ProcessBuilder(cmd);
        // local 模式密码经环境变量传递（不进命令行参数，避免出现在进程列表）；
        // docker 模式该变量只作用于本机 docker CLI 进程，容器内密码仍由 -e PGPASSWORD 提供
        pb.environment().put("PGPASSWORD", dbPass);
        pb.redirectErrorStream(false);
        if (outputFile != null) {
            pb.redirectOutput(outputFile.toFile());
        } else {
            // 不重定向到文件时必须丢弃 stdout：pg_restore 会输出对象级明细，
            // 无人消费会塞满 OS 管道（约 64KB）导致进程阻塞挂起；错误信息走 stderr 单独读取
            pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        }
        if (inputFile != null) {
            pb.redirectInput(inputFile.toFile());
        }
        Process process;
        try {
            process = pb.start();
        } catch (IOException e) {
            String hint = localMode()
                ? "无法启动 pg_dump/pg_restore（" + e.getMessage()
                    + "），请确认运行环境已安装与数据库同版本（16.x）的 postgresql-client"
                : "无法启动 docker 命令（" + e.getMessage()
                    + "），请确认宿主机已安装 docker 且当前进程有执行权限";
            throw new ServiceException(action + "失败：" + hint);
        }
        String errorOutput;
        try (java.io.InputStream es = process.getErrorStream()) {
            boolean finished = process.waitFor(PROCESS_TIMEOUT_MINUTES, TimeUnit.MINUTES);
            if (!finished) {
                process.destroyForcibly();
                throw new ServiceException(action + "超时（超过 " + PROCESS_TIMEOUT_MINUTES + " 分钟），已终止");
            }
            errorOutput = new String(es.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ServiceException(action + "被中断");
        } catch (IOException e) {
            throw new ServiceException(action + "失败：读取错误输出失败（" + e.getMessage() + "）");
        }
        if (process.exitValue() != 0) {
            log.error("[备份恢复] {} 失败：exit={}, cmd={}, stderr={}", action, process.exitValue(), cmd, errorOutput);
            String brief = errorOutput == null || errorOutput.isBlank() ? "无错误详情" : errorOutput.lines().findFirst().orElse("");
            throw new ServiceException(action + "失败：" + brief);
        }
    }

    /** 检查 docker 容器在运行（与脚本前置检查一致）。 */
    private void checkContainerRunning() {
        ProcessBuilder pb = new ProcessBuilder("docker", "ps", "--format", "{{.Names}}");
        try {
            Process process = pb.start();
            String names;
            try (java.io.InputStream in = process.getInputStream()) {
                names = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                process.waitFor(30, TimeUnit.SECONDS);
            }
            if (process.exitValue() != 0 || !names.lines().anyMatch(n -> n.trim().equals(container))) {
                throw new ServiceException("数据库容器 [" + container + "] 未运行，请检查 docker 环境或 panjia.backup.container 配置");
            }
        } catch (IOException | InterruptedException e) {
            throw new ServiceException("检查 docker 容器状态失败（" + e.getMessage() + "）");
        }
    }

    /** 备份目录（不存在则创建）。 */
    private Path ensureBackupDir() {
        try {
            Path dir = Paths.get(backupDir).toAbsolutePath().normalize();
            if (!Files.exists(dir)) {
                Files.createDirectories(dir);
            }
            return dir;
        } catch (IOException e) {
            throw new ServiceException("备份目录不可用：" + e.getMessage());
        }
    }

    /** 文件名白名单校验 + 文件存在性检查。 */
    private Path requireValidFile(String fileName) {
        if (fileName == null || !FILE_NAME_PATTERN.matcher(fileName).matches()) {
            throw new ServiceException("非法的备份文件名：" + fileName);
        }
        Path file = ensureBackupDir().resolve(fileName).normalize();
        if (!file.startsWith(ensureBackupDir()) || !Files.isRegularFile(file)) {
            throw new ServiceException("备份文件不存在：" + fileName);
        }
        return file;
    }

    /** 按保留份数清理旧备份（同库名前缀，仅删本目录内文件）。 */
    private void cleanupOldBackups(Path dir) {
        try (Stream<Path> files = Files.list(dir)) {
            List<Path> dumps = new ArrayList<>(files
                .filter(p -> Files.isRegularFile(p) && p.getFileName().toString().startsWith(dbName + "_")
                    && p.getFileName().toString().endsWith(".dump"))
                .toList());
            dumps.sort((a, b) -> Long.compare(lastModified(b), lastModified(a)));
            for (int i = keep; i < dumps.size(); i++) {
                Files.deleteIfExists(dumps.get(i));
                log.info("[备份恢复] 清理旧备份：{}", dumps.get(i).getFileName());
            }
        } catch (IOException e) {
            log.warn("[备份恢复] 清理旧备份失败（不影响本次备份）", e);
        }
    }

    private long lastModified(Path p) {
        try {
            return Files.getLastModifiedTime(p).toMillis();
        } catch (IOException e) {
            return 0L;
        }
    }

    private String humanSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return String.format("%.1f KB", bytes / 1024.0);
        }
        if (bytes < 1024L * 1024 * 1024) {
            return String.format("%.1f MB", bytes / 1024.0 / 1024);
        }
        return String.format("%.2f GB", bytes / 1024.0 / 1024 / 1024);
    }

    /**
     * 还原 DB 后清缓存：Redis FLUSHDB + Caffeine 本地缓存 clearAll。
     * <p>
     * pg_restore --clean 整库覆盖后，Redis 里的字典/配置（sys_config、sys_dict、sys_dict_type
     * 无 TTL 永不过期）、部门/客户端缓存（#30d 最长 30 天）以及登录会话（Authorization:login:*）
     * 全部作废，必须手动清空，否则用户看到的是还原前的旧数据。
     * <p>
     * FLUSHDB 会同时清掉 Caffeine 一级缓存吗？不会。PlusSpringCacheManager 的 Caffeine 是本地内存，
     * FLUSHDB 只清 Redis。因此需要先 FLUSHDB（清 Redis），再 CacheManager.getCacheNames() → clear()
     * （清 Caffeine）。若 CacheManager 不可用则仅 FLUSHDB，Caffeine 30 秒后自然过期自愈。
     */
    private void clearCaches() {
        // 1. Redis FLUSHDB — 清掉所有 Spring Cache + 登录会话 + 限流计数等非 CacheManager 管理的 key
        try {
            RedissonClient redisson = RedisUtils.getClient();
            if (redisson != null) {
                // 先 keycount 看看有多少，再 flushdb（flushdb 返回 void，不直接给日志）
                long before = redisson.getKeys().count();
                redisson.getKeys().flushdb();
                log.info("[备份恢复] Redis FLUSHDB 完成，之前 {} 个 key 已清空", before);
            }
        } catch (Exception e) {
            log.warn("[备份恢复] Redis FLUSHDB 失败（不影响还原结果，但缓存可能脏）：{}", e.getMessage());
        }

        // 2. Caffeine 本地缓存 — 清掉所有 Spring Cache 对应的一级缓存
        if (cacheManager != null) {
            try {
                int cleared = 0;
                for (String name : cacheManager.getCacheNames()) {
                    org.springframework.cache.Cache cache = cacheManager.getCache(name);
                    if (cache != null) {
                        cache.clear();
                        cleared++;
                    }
                }
                log.info("[备份恢复] Caffeine 本地缓存清理完成，共 {} 个 cache", cleared);
            } catch (Exception e) {
                log.warn("[备份恢复] Caffeine 本地缓存清理失败（不影响还原结果，30 秒后自然过期）：{}", e.getMessage());
            }
        }
    }
}
