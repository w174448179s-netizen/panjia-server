package com.panjia.people.dto;

import lombok.Data;
import org.dromara.common.translation.annotation.Translation;
import org.dromara.common.translation.constant.TransConstant;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 考勤修改申请单视图（本人记录 / 人事审批详情共用）。
 */
@Data
public class AttendanceRectifyVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 记录 ID */
    private Long id;

    /** 申请人员工 ID */
    private Long employeeId;

    /** 工号 */
    private String employeeCode;

    /** 姓名 */
    private String employeeName;

    /** 考勤月份（当月 1 日） */
    private LocalDate attendMonth;

    /** 修改明细（字段/字段名/原值/新值） */
    private List<RectifyChangeItem> changes;

    /** 涉及日期（逗号分隔 yyyy-MM-dd） */
    private String involveDates;

    /** 修改说明 */
    private String reason;

    /** 截图 ossId 列表（逗号分隔） */
    private String ossIds;

    /** 截图访问地址（逗号分隔，与 ossIds 一一对应，由翻译自动填充） */
    @Translation(type = TransConstant.OSS_ID_TO_URL, mapper = "ossIds")
    private String ossUrls;

    /** 审批状态 */
    private String status;

    /** 提交人用户ID */
    private Long submitBy;

    /** 提交时间 */
    private LocalDateTime submitTime;

    /** 审批人用户ID */
    private Long approveBy;

    /** 审批时间 */
    private LocalDateTime approveTime;

    /** 驳回原因 */
    private String rejectReason;

    /** 创建时间 */
    private LocalDateTime createTime;
}
