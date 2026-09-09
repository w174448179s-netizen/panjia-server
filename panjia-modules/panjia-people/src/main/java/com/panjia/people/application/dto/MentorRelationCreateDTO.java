package com.panjia.people.application.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 师徒关系建立 DTO。
 */
@Data
public class MentorRelationCreateDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 师傅员工 ID */
    @NotNull(message = "师傅员工 ID 不能为空")
    private Long mentorId;

    /** 徒弟员工 ID */
    @NotNull(message = "徒弟员工 ID 不能为空")
    private Long apprenticeId;

    /** 徒弟行业经验年数（>=2 年有招聘奖励资格） */
    @NotNull(message = "行业经验年数不能为空")
    @DecimalMin(value = "0", message = "行业经验年数不能为负")
    private BigDecimal industryYears;

    /** 推荐日期 */
    @NotNull(message = "推荐日期不能为空")
    private LocalDate recommendDate;
}
