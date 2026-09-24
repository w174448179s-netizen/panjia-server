package com.panjia.people.service;

import com.panjia.people.domain.PeopleImportBatch;
import com.panjia.people.domain.PeopleImportIssue;

import java.time.LocalDate;
import java.util.List;

/**
 * 员工导入服务（V6.0：员工导入回迁 people 域本域承接）。
 * <p>
 * 两阶段执行：阶段 A（同步）诊断（解析落 raw + 基础/业务校验，阻断问题致批次 FAILED）；
 * 阶段 B（异步）提交到线程池后台执行，单一大原子事务逐行复用
 * {@link EmployeeService#createEmployee} 落地，每 50 行更新进度（前端轮询可查）；
 * 任一行失败整批回滚不留半成品。
 */
public interface EmployeeImportService {

    /**
     * 从文件字节执行员工导入。
     * <p>
     * 新员工走创建（算薪事实生效日=入职日）；已有工号走覆盖更新，
     * 算薪数据列经 changeFact 时间线切换（旧事实结束日期、新事实生效日均置为生效时间）。
     *
     * @param content      文件字节内容（XLSX/CSV）
     * @param fileName     原始文件名
     * @param operatorId   操作人 ID
     * @param effectiveDate 覆盖导入的生效时间（可选，默认当前时间）
     * @return 导入批次 ID（成功或失败均返回，问题清单可查）
     */
    Long importEmployees(byte[] content, String fileName, Long operatorId, LocalDate effectiveDate);

    /**
     * 员工导入批次列表（按创建时间倒序）。
     *
     * @return 批次集合
     */
    List<PeopleImportBatch> listBatches();

    /**
     * 批次详情。
     *
     * @param batchId 批次 ID
     * @return 批次
     */
    PeopleImportBatch getBatch(Long batchId);

    /**
     * 批次问题清单（按行号升序）。
     *
     * @param batchId 批次 ID
     * @return 问题集合
     */
    List<PeopleImportIssue> listIssues(Long batchId);
}
