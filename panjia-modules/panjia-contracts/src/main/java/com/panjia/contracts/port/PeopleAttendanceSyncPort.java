package com.panjia.contracts.port;

import com.panjia.contracts.dto.AttendanceSummarySyncDTO;

import java.util.List;

/**
 * 员工域考勤汇总同步端口（import → people）。
 * <p>
 * 考勤（ATTENDANCE）导入批次归档（ARCHIVED，含自动归档/手动归档/重归一化）
 * 后，导入域调用本端口把钉钉《月度汇总》的月度指标写入员工域
 * pj_people_attendance（一人一月一行），实现"导入即同步"，
 * 人事无需再手工登记。
 */
public interface PeopleAttendanceSyncPort {

    /**
     * 按期间同步月度考勤汇总（upsert：同员工同月存在则覆盖更新，不存在则新增）。
     * <p>
     * 数据来源标记 dataSource='DINGTALK'；工号无法匹配员工档案的行忽略并记录告警。
     * 同步失败不阻断导入主流程（导入域负责捕获）。
     *
     * @param period    归属期间（YYYY-MM）
     * @param summaries 月度指标列表（一行一人）
     */
    void syncAttendanceSummaries(String period, List<AttendanceSummarySyncDTO> summaries);
}
