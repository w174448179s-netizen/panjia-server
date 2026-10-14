package com.panjia.people.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 考勤修改申请提交请求（经纪人本人发起）。
 * <p>
 * 员工身份由服务端按登录用户强制确定，不接受前端传员工 ID。
 */
@Data
public class AttendanceRectifySubmitDTO {

    /** 考勤月份（yyyy-MM-dd，当月 1 日） */
    @NotBlank(message = "考勤月份不能为空")
    private String attendMonth;

    /** 修改明细（至少一项；field 限定月汇总指标白名单，newValue 为字符串形式） */
    @NotEmpty(message = "修改项不能为空")
    @Valid
    private List<RectifyChangeItem> changes;

    /** 涉及日期（逗号分隔 yyyy-MM-dd，可空） */
    @Size(max = 500, message = "涉及日期长度不能超过 500 个字符")
    private String involveDates;

    /** 修改说明（必填） */
    @NotBlank(message = "修改说明不能为空")
    @Size(max = 500, message = "修改说明长度不能超过 500 个字符")
    private String reason;

    /** 截图 ossId 列表（逗号分隔，至少 1 张） */
    @NotBlank(message = "请至少上传 1 张截图")
    @Size(max = 1000, message = "截图数量过多")
    private String ossIds;
}
