package com.panjia.people.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.annotation.SaMode;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.PageResult;
import org.dromara.common.core.domain.R;
import org.dromara.common.log.annotation.Log;
import org.dromara.common.log.enums.BusinessType;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.common.web.core.BaseController;
import com.panjia.people.dto.AttendanceRectifyQuery;
import com.panjia.people.dto.AttendanceRectifySubmitDTO;
import com.panjia.people.dto.AttendanceRectifyVO;
import com.panjia.people.service.AttendanceRectifyService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 考勤修改申请（经纪人发起 → 人事审批）。
 * <p>
 * 本人接口（/my/**）全员可访问，服务端按登录用户强制本人数据；
 * 详情接口（/{id}）仅人事/总监持 people:attendance:list 权限，供「我的待办」审批弹窗加载。
 * 审批动作（通过/驳回）全部经工作流待办由引擎判权办理，本控制器不提供直批入口。
 */
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/people/attendance/rectify")
public class AttendanceRectifyController extends BaseController {

    private final AttendanceRectifyService rectifyService;

    /**
     * 本人申请记录分页查询。
     *
     * @param query     月份区间/状态条件；员工身份由服务端按登录用户确定
     * @param pageQuery 分页参数
     * @return 本人申请分页
     */
    @SaCheckPermission("people:attendance:my:query")
    @GetMapping("/my/page")
    public R<PageResult<AttendanceRectifyVO>> myPage(AttendanceRectifyQuery query, PageQuery pageQuery) {
        return R.ok(rectifyService.myPage(LoginHelper.getUserId(), query, pageQuery));
    }

    /**
     * 提交考勤修改申请（发起人事审批流程）。
     *
     * @param dto 申请内容（月汇总指标调整 + 截图 + 说明）
     * @return 申请单 ID
     */
    @SaCheckPermission("people:attendance:my:query")
    @Log(title = "考勤修改申请", businessType = BusinessType.INSERT)
    @PostMapping("/my/submit")
    public R<Long> submit(@Validated @RequestBody AttendanceRectifySubmitDTO dto) {
        return R.ok("提交成功，请等待人事审批", rectifyService.submit(LoginHelper.getUserId(), dto));
    }

    /**
     * 管理端申请记录分页（人事/总监，考勤管理「修改记录」Tab 用）。
     *
     * @param query     月份区间/状态/员工姓名条件
     * @param pageQuery 分页参数
     * @return 申请分页
     */
    @SaCheckPermission("people:attendance:list")
    @GetMapping("/page")
    public R<PageResult<AttendanceRectifyVO>> pageManage(AttendanceRectifyQuery query, PageQuery pageQuery) {
        return R.ok(rectifyService.pageManage(query, pageQuery));
    }

    /**
     * 申请单详情：人事/总监（people:attendance:list）可查看全部，供工作流审批弹窗/考勤管理「修改记录」用；
     * 本人（people:attendance:my:query）可查看自己的申请，服务端做归属校验。
     *
     * @param id 申请单 ID
     * @return 申请单详情
     */
    @SaCheckPermission(value = {"people:attendance:my:query", "people:attendance:list"}, mode = SaMode.OR)
    @GetMapping("/{id}")
    public R<AttendanceRectifyVO> getByBizId(@PathVariable Long id) {
        return R.ok(rectifyService.getByBizId(LoginHelper.getUserId(), id));
    }

    /**
     * 认领当前节点待办（拥有人事/总监角色但不在授权快照时，如事后补赋人事角色）。
     * <p>认领成功后前端重新拉取本人待办即可打开审批弹窗。
     */
    @SaCheckPermission("people:attendance:list")
    @Log(title = "考勤修改申请任务认领", businessType = BusinessType.UPDATE)
    @PostMapping("/{id}/claim")
    public R<Boolean> claim(@PathVariable Long id) {
        return rectifyService.claim(id)
            ? R.ok("认领成功", true)
            : R.fail("该申请单当前无待办，或您不具备当前节点所需角色", false);
    }
}
