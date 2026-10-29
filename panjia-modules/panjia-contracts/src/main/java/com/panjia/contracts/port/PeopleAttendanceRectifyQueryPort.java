package com.panjia.contracts.port;

/**
 * 考勤修改申请跨域查询端口（import → people）。
 * <p>
 * 考勤导入前卡点：当月已存在有效修改申请（待审批/已通过）时禁止导入，
 * 避免导入覆盖经纪人已生效的考勤修改结果（月度汇总为同人同月覆盖写）。
 * 与「导入前期间封账卡点」（{@link PeriodCloseQueryPort}）同构。
 */
public interface PeopleAttendanceRectifyQueryPort {

    /**
     * 期间是否存在有效的考勤修改申请（待审批 SUBMITTED 或已通过 APPROVED）。
     *
     * @param period 期间 YYYY-MM
     * @return true=存在有效修改申请（导入应被拒绝）
     */
    boolean hasEffectiveRectify(String period);
}
