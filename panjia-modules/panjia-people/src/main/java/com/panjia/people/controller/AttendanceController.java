package com.panjia.people.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.PageResult;
import org.dromara.common.core.domain.R;
import org.dromara.common.log.annotation.Log;
import org.dromara.common.log.enums.BusinessType;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.common.web.core.BaseController;
import com.panjia.people.dto.AttendanceQuery;
import com.panjia.people.dto.AttendanceSaveDTO;
import com.panjia.people.dto.AttendanceVO;
import com.panjia.people.service.AttendanceService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;

/**
 * 考勤明细（员工域）。
 * <p>
 * 管理端（/list、/{id}、增改删）仅人事/总监持 people:attendance:* 权限；
 * 本人接口（/my/**）全员可访问，服务端按登录用户强制只返回本人数据。
 */
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/people/attendance")
public class AttendanceController extends BaseController {

    private final AttendanceService attendanceService;

    /**
     * 管理端分页查询考勤明细。
     *
     * @param query     筛选条件
     * @param pageQuery 分页参数
     * @return 考勤明细分页
     */
    @SaCheckPermission("people:attendance:list")
    @GetMapping("/list")
    public R<PageResult<AttendanceVO>> list(AttendanceQuery query, PageQuery pageQuery) {
        return R.ok(attendanceService.pageManage(query, pageQuery));
    }

    /**
     * 管理端考勤明细详情。
     *
     * @param id 记录 ID
     * @return 考勤明细
     */
    @SaCheckPermission("people:attendance:list")
    @GetMapping("/{id}")
    public R<AttendanceVO> getInfo(@PathVariable Long id) {
        return R.ok(attendanceService.getById(id));
    }

    /**
     * 新增考勤记录（人工登记）。
     *
     * @param dto 新增请求
     * @return 记录 ID
     */
    @SaCheckPermission("people:attendance:add")
    @Log(title = "考勤明细", businessType = BusinessType.INSERT)
    @PostMapping
    public R<Long> add(@Validated @RequestBody AttendanceSaveDTO dto) {
        return R.ok("新增成功", attendanceService.create(dto));
    }

    /**
     * 修改考勤记录。
     *
     * @param id  记录 ID
     * @param dto 修改请求
     * @return 结果
     */
    @SaCheckPermission("people:attendance:edit")
    @Log(title = "考勤明细", businessType = BusinessType.UPDATE)
    @PutMapping("/{id}")
    public R<Void> edit(@PathVariable Long id, @Validated @RequestBody AttendanceSaveDTO dto) {
        attendanceService.update(id, dto);
        return R.ok();
    }

    /**
     * 删除考勤记录（支持批量，逗号分隔）。
     *
     * @param ids 记录 ID 数组
     * @return 结果
     */
    @SaCheckPermission("people:attendance:remove")
    @Log(title = "考勤明细", businessType = BusinessType.DELETE)
    @DeleteMapping("/{ids}")
    public R<Void> remove(@PathVariable Long[] ids) {
        attendanceService.deleteByIds(Arrays.asList(ids));
        return R.ok();
    }

    // ==================== 本人查询（全员，服务端强制本人数据） ====================

    /**
     * 本人考勤明细分页查询。
     *
     * @param query     仅取考勤结果/日期区间；员工身份由服务端按登录用户确定
     * @param pageQuery 分页参数
     * @return 本人考勤明细分页
     */
    @SaCheckPermission("people:attendance:my:query")
    @GetMapping("/my/list")
    public R<PageResult<AttendanceVO>> myList(AttendanceQuery query, PageQuery pageQuery) {
        return R.ok(attendanceService.pageMy(LoginHelper.getUserId(), query, pageQuery));
    }

    /**
     * 本人考勤明细详情（访问他人记录按不存在处理）。
     *
     * @param id 记录 ID
     * @return 本人考勤明细
     */
    @SaCheckPermission("people:attendance:my:query")
    @GetMapping("/my/{id}")
    public R<AttendanceVO> myGetInfo(@PathVariable Long id) {
        return R.ok(attendanceService.getMy(id, LoginHelper.getUserId()));
    }
}
