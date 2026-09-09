package com.panjia.people.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 职级记录 —— 值对象（追加式写入，对应 pj_people_level）。
 * <p>
 * 每次职级变更新增一条记录（带 effectiveFrom），禁止 UPDATE 旧记录。
 */
@Data
@TableName("pj_people_level")
public class EmployeeLevel implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 主键，雪花 ID */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 关联员工 ID */
    private Long employeeId;

    /** 职级编码：A0~A5 / S1 / S2 / DIRECTOR */
    private String levelCode;

    /** 职级名称（冗余展示） */
    private String levelName;

    /** 底薪 */
    private BigDecimal baseSalary;

    /** 基础提成比例 */
    private BigDecimal commissionRate;

    /** 社保个人承担比例 */
    private BigDecimal socialInsuranceRatio;

    /** 生效日期 */
    private LocalDate effectiveFrom;

    /** 失效日期（NULL = 当前有效） */
    private LocalDate effectiveTo;

    /** 变更原因（晋升/降级/初始化） */
    private String changeReason;

    /** 创建人 */
    private String createdBy;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /**
     * 判断在指定时点是否有效。
     *
     * @param pointInTime 时点
     * @return true 表示该时点本记录有效
     */
    public boolean isEffectiveAt(LocalDate pointInTime) {
        boolean afterFrom = !effectiveFrom.isAfter(pointInTime);
        boolean beforeTo = effectiveTo == null || !effectiveTo.isBefore(pointInTime);
        return afterFrom && beforeTo;
    }
}
