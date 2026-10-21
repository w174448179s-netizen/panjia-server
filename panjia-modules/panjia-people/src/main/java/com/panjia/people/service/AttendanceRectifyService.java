package com.panjia.people.service;

import org.dromara.common.core.domain.PageResult;
import org.dromara.common.mybatis.core.page.PageQuery;
import com.panjia.contracts.port.PeopleAttendanceRectifyQueryPort;
import com.panjia.people.dto.AttendanceRectifyQuery;
import com.panjia.people.dto.AttendanceRectifySubmitDTO;
import com.panjia.people.dto.AttendanceRectifyVO;

/**
 * 考勤修改申请（经纪人发起 → 人事审批 → 生效回写月考勤）。
 * <p>
 * 审批动作全部经「我的待办」由 Warm-Flow 引擎按 flow_user 名单判权办理，
 * 业务侧只提供 申请提交 / 本人查询 / 审批详情查询 / 工作流事件回写，无业务直批入口。
 * 另实现 {@link PeopleAttendanceRectifyQueryPort} 供导入域做「当月已有修改申请则禁止导入」卡点。
 */
public interface AttendanceRectifyService extends PeopleAttendanceRectifyQueryPort {

    /**
     * 本人申请记录分页（服务端按登录用户强制本人数据）。
     *
     * @param loginUserId 登录用户 ID
     * @param query       月份区间/状态条件
     * @param pageQuery   分页参数
     * @return 本人申请分页
     */
    PageResult<AttendanceRectifyVO> myPage(Long loginUserId, AttendanceRectifyQuery query, PageQuery pageQuery);

    /**
     * 管理端申请记录分页（人事/总监，考勤管理「修改记录」Tab 用）。
     *
     * @param query     月份区间/状态/员工姓名条件
     * @param pageQuery 分页参数
     * @return 申请分页
     */
    PageResult<AttendanceRectifyVO> pageManage(AttendanceRectifyQuery query, PageQuery pageQuery);

    /**
     * 提交修改申请（发起 Warm-Flow 流程，人事审核）。
     *
     * @param loginUserId 登录用户 ID（员工身份服务端强制解析）
     * @param dto         申请内容
     * @return 申请单 ID
     */
    Long submit(Long loginUserId, AttendanceRectifySubmitDTO dto);

    /**
     * 申请单详情：人事/总监持 people:attendance:list 可查看全部；
     * 本人（people:attendance:my:query）仅可查看本人申请，服务端做归属校验。
     *
     * @param loginUserId 登录用户 ID
     * @param id          申请单 ID
     * @return 申请单详情
     */
    AttendanceRectifyVO getByBizId(Long loginUserId, Long id);

    /**
     * 工作流事件回写：finish→生效修改+APPROVED；back→REJECTED 并清理实例；
     * cancel/invalid/termination→CANCELED（幂等）。
     *
     * @param bizId   申请单 ID
     * @param status  事件类型
     * @param handler 办理人
     * @param message 办理意见
     */
    void handleWorkflowEvent(Long bizId, String status, String handler, String message);

    /**
     * 认领当前节点待办（拥有人事/总监角色但不在授权快照时，如事后补赋人事角色）。
     * <p>认领成功后前端重新拉取本人待办即可打开审批弹窗。
     *
     * @param id 申请单 ID
     * @return true=认领成功；false=当前无待办或角色不匹配
     */
    boolean claim(Long id);
}
