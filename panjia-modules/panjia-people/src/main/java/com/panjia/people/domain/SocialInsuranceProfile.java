package com.panjia.people.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 社保档案 —— 值对象（对应 pj_people_social_insurance）。
 * <p>
 * 社保双口径（业务需求 §14.2）：
 * <ul>
 *   <li>个人承担 = socialBaseAmount × personalRatio → 从工资扣除</li>
 *   <li>公司承担 = socialBaseAmount × companyRatio → 归集部门收支表</li>
 * </ul>
 */
@Data
@TableName("pj_people_social_insurance")
public class SocialInsuranceProfile implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 主键，雪花 ID */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 关联员工 ID（一对一） */
    private Long employeeId;

    /** 社保基数（默认 1637.15） */
    private BigDecimal socialBaseAmount;

    /** 个人承担比例 */
    private BigDecimal personalRatio;

    /** 公司承担比例（= 1 - personalRatio） */
    private BigDecimal companyRatio;

    /** 公积金自缴金额 */
    private BigDecimal housingFundAmount;

    /** 商业保险费（月） */
    private BigDecimal commercialInsuranceAmount;

    /** 宿舍管理费（月） */
    private BigDecimal dormitoryFee;

    /** 生效日期 */
    private LocalDate effectiveFrom;

    /** 失效日期（NULL = 当前有效） */
    private LocalDate effectiveTo;

    /** 创建人 */
    private String createdBy;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 更新人 */
    private String updatedBy;

    /** 更新时间 */
    private LocalDateTime updatedAt;

    /**
     * 计算个人承担社保金额。
     *
     * @return 个人承担金额（保留 2 位小数）
     */
    public BigDecimal calculatePersonalAmount() {
        return socialBaseAmount.multiply(personalRatio).setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * 计算公司承担社保金额（归集部门收支）。
     *
     * @return 公司承担金额（保留 2 位小数）
     */
    public BigDecimal calculateCompanyAmount() {
        return socialBaseAmount.multiply(companyRatio).setScale(2, RoundingMode.HALF_UP);
    }
}
