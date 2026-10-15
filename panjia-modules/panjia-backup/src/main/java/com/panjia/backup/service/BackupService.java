package com.panjia.backup.service;

import com.panjia.backup.domain.vo.SysBackupFileVo;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Path;
import java.util.List;

/**
 * 数据库备份恢复服务（系统管理 → 备份恢复）。
 * <p>
 * 通过 Docker 容器内 pg_dump / pg_restore 执行 PostgreSQL 逻辑备份与还原，
 * 备份产物（pg 自定义格式 .dump）落服务器本地目录，支持列表/备份/还原/下载/删除，
 * 与 script/db/backup.sh、restore.sh 脚本同口径（容器、库名、账号可用配置覆盖）。
 */
public interface BackupService {

    /**
     * 备份文件列表（按时间倒序）。
     *
     * @return 备份文件集合
     */
    List<SysBackupFileVo> listFiles();

    /**
     * 执行备份（pg_dump -Fc 自定义格式），完成后按保留份数清理旧备份。
     *
     * @return 备份文件名
     */
    String createBackup();

    /**
     * 还原数据库（pg_restore --clean --if-exists，先删后建，整库覆盖）。
     * <p>危险操作：还原期间数据库对象会被重建，在线请求可能短暂报错。
     *
     * @param fileName 备份文件名
     */
    void restoreBackup(String fileName);

    /**
     * 上传 dump 文件直接还原：先 pg_restore --list 校验归档完整性，
     * 再整库覆盖还原。临时文件用完即删，不落备份目录。
     *
     * @param file 上传的 pg_dump 自定义格式（.dump）备份文件
     * @return 原始文件名（仅用于回显/日志，不参与磁盘路径）
     */
    String restoreFromUpload(MultipartFile file);

    /**
     * 删除备份文件。
     *
     * @param fileName 备份文件名
     */
    void deleteBackup(String fileName);

    /**
     * 解析备份文件磁盘路径（下载用），含文件名合法性校验。
     *
     * @param fileName 备份文件名
     * @return 磁盘路径
     */
    Path resolveBackupFile(String fileName);
}
