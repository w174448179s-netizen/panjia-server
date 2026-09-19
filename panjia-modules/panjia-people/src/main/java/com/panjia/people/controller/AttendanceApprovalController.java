package com.panjia.people.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.panjia.people.dto.AttendanceApprovalVO;
import com.panjia.people.service.AttendanceApprovalService;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.dromara.common.log.annotation.Log;
import org.dromara.common.log.enums.BusinessType;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.common.web.core.BaseController;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 考勤审批：人事提交当月考勤 → warm-flow 考勤月度审批（attendance_approval）
 * → 总监在「我的待办」办理（24h 超时自动通过）→ 办结回写状态。
 * <p>
 * 审批动作（通过/驳回）全部经「我的待办」由引擎按 flow_user 名单判权办理，
 * 本控制器不再提供业务直批端点；总监审核节点仅审异常考勤行（快照见 VO）。
 * 总监审批通过后该期间方可创建薪酬批次进入算薪（薪酬域经
 * PeopleAttendanceApprovalQueryPort 卡点校验）。
 */
@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/people/attendance/approval")
public class AttendanceApprovalController extends BaseController {

    private final AttendanceApprovalService approvalService;

    /** 查询期间审批状态（仅登录校验：算薪操作员可能无考勤明细权限，用于创建批次前的无考勤确认） */
    @GetMapping("/{period}")
    public R<AttendanceApprovalVO> getByPeriod(@PathVariable String period) {
        return R.ok(approvalService.getByPeriod(period));
    }

    /** 按审批单 ID 查询（工作流办理弹窗详情组件用，含异常考勤快照） */
    @SaCheckPermission("people:attendance:list")
    @GetMapping("/detail/{id}")
    public R<AttendanceApprovalVO> getByBizId(@PathVariable Long id) {
        return R.ok(approvalService.getByBizId(id));
    }

    /**
     * 人事提交当月考勤审批（发起/重提 warm-flow 流程）。
     * 提交入口仅在考勤明细管理页（people:attendance:list 可见），
     * 后续审批办理由工作流引擎判权。
     */
    @Log(title = "考勤审批", businessType = BusinessType.INSERT)
    @PostMapping("/submit")
    public R<Void> submit(@RequestBody Map<String, String> body) {
        approvalService.submit(body.get("period"), LoginHelper.getUserId());
        return R.ok("已提交总监审批");
    }
}
