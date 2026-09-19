package com.panjia.people.dto;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 绩效积分查询条件。
 */
@Data
public class ScoreQuery implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 工号（模糊） */
    private String employeeCode;

    /** 姓名（模糊） */
    private String employeeName;

    /** 员工 ID（精确） */
    private Long employeeId;

    /** 部门 ID（含下级） */
    private Long deptId;

    /** 月份区间起点（yyyy-MM-dd，前端传当月 1 日） */
    private String monthStart;

    /** 月份区间终点（yyyy-MM-dd，前端传当月 1 日） */
    private String monthEnd;
}
