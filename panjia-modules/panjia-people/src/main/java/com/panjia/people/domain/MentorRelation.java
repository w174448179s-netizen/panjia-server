package com.panjia.people.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 师徒关系 —— 独立实体（对应 pj_people_mentor_relation，独立生命周期，不属 Employee 聚合）。
 * <p>
 * 业务规则（业务需求 §4.2）：
 * <ol>
 *   <li>徒弟行业经验 &gt;= 2 年 → 师傅获得招聘奖励资格</li>
 *   <li>师傅每推荐 1 人 +2%，上限 +10%（最多 5 人）</li>
 *   <li>徒弟离职 → 关系失效（isActive=false），已发奖励不追回</li>
 * </ol>
 */
@Data
@TableName("pj_people_mentor_relation")
public class MentorRelation implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 主键，雪花 ID */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 师傅 employee_id */
    private Long mentorId;

    /** 徒弟 employee_id */
    private Long apprenticeId;

    /** 徒弟行业经验年数（推荐时） */
    private BigDecimal apprenticeIndustryYears;

    /** 推荐日期 */
    private LocalDate recommendDate;

    /** 是否有效（徒弟离职 → false） */
    @TableField("is_active")
    private boolean active;

    /** 失效时间 */
    private LocalDateTime deactivatedAt;

    /** 创建人 */
    private String createdBy;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /**
     * 判断是否满足招聘奖励条件（行业经验 &gt;= 2 年）。
     *
     * @return true 表示合格
     */
    public boolean isQualified() {
        return apprenticeIndustryYears != null
            && apprenticeIndustryYears.compareTo(new BigDecimal("2.0")) >= 0;
    }

    /**
     * 失效（徒弟离职时调用）。
     */
    public void deactivate() {
        this.active = false;
        this.deactivatedAt = LocalDateTime.now();
    }
}
