package com.panjia.people.service;

import com.panjia.contracts.port.PeopleAttendanceSyncPort;
import org.dromara.common.core.domain.PageResult;
import org.dromara.common.mybatis.core.page.PageQuery;
import com.panjia.people.dto.AttendanceQuery;
import com.panjia.people.dto.AttendanceSaveDTO;
import com.panjia.people.dto.AttendanceVO;

import java.util.Collection;

/**
 * 月考勤汇总服务（一员工一月一行，对齐钉钉月度汇总，服务薪酬扣款）。
 * <p>
 * 管理端面向人事/总监；本人查询面向全员，数据隔离在服务端按登录用户强制完成。
 * 同时实现 {@link PeopleAttendanceSyncPort}：考勤导入归档后自动 upsert 本表。
 */
public interface AttendanceService extends PeopleAttendanceSyncPort {

    /**
     * 管理端分页查询（员工/部门/结果/日期区间）。
     *
     * @param query     筛选条件
     * @param pageQuery 分页参数
     * @return 考勤明细分页
     */
    PageResult<AttendanceVO> pageManage(AttendanceQuery query, PageQuery pageQuery);

    /**
     * 考勤明细详情（管理端）。
     *
     * @param id 记录 ID
     * @return 考勤明细
     */
    AttendanceVO getById(Long id);

    /**
     * 新增考勤记录（人工登记，data_source=MANUAL）。
     *
     * @param dto 新增请求
     * @return 记录 ID
     */
    Long create(AttendanceSaveDTO dto);

    /**
     * 修改考勤记录（员工归属不可改；日期变更仍受同人同日唯一约束保护）。
     *
     * @param id  记录 ID
     * @param dto 修改请求
     */
    void update(Long id, AttendanceSaveDTO dto);

    /**
     * 物理删除考勤记录（支持批量）。
     *
     * @param ids 记录 ID 集合
     */
    void deleteByIds(Collection<Long> ids);

    /**
     * 本人分页查询：强制只返回登录用户对应员工的记录。
     *
     * @param loginUserId 登录系统用户 ID（sys_user.user_id）
     * @param query       仅取考勤结果/日期区间条件，员工身份条件一律忽略
     * @param pageQuery   分页参数
     * @return 本人考勤明细分页（无员工档案返回空页）
     */
    PageResult<AttendanceVO> pageMy(Long loginUserId, AttendanceQuery query, PageQuery pageQuery);

    /**
     * 本人详情：非本人记录按不存在处理。
     *
     * @param id          记录 ID
     * @param loginUserId 登录系统用户 ID
     * @return 本人考勤明细
     */
    AttendanceVO getMy(Long id, Long loginUserId);
}
