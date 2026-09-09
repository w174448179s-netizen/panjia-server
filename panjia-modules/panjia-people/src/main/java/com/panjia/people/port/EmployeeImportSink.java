package com.panjia.people.port;

import com.panjia.people.dto.ValidatedEmployeeRow;

import java.util.List;

/**
 * 员工导入落地端口（导入域 → people 域的写入 SPI）。
 * <p>
 * 员工导入收敛在导入域 source_type=EMPLOYEE：导入域负责批次与原始归档，
 * 校验通过的行在同一事务内回调本端口落地——部门树 + Employee + sys_user +
 * 岗位/角色 + 8 条 fact + change_log 原子提交；任一失败整体回滚。
 * <p>
 * people 域不提供导入接口、不建导入批次表。
 */
public interface EmployeeImportSink {

    /**
     * 落地一批校验通过的员工行。
     *
     * @param rows 校验通过行（必填/格式/枚举/工号唯一/师傅存在等已由导入域校验）
     * @return 新建员工 ID 集合
     */
    List<Long> apply(List<ValidatedEmployeeRow> rows);
}
