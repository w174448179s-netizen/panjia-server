package com.panjia.payroll.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.panjia.common.constant.PanjiaTransConstant;
import lombok.Data;
import org.dromara.common.translation.annotation.Translation;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 手工录入项（奖金/其他收入/其他支出） */
@Data
@TableName("pj_payroll_manual_item")
public class ManualItem implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 生效起始月 YYYY-MM（含）；算薪取 startMonth ≤ 算薪月 ≤ endMonth 的审批通过项 */
    private String startMonth;

    /** 生效截止月 YYYY-MM（含）；同提成点调整口径 */
    private String endMonth;

    private Long employeeId;
    private ManualItemType itemType;
    private String subType;
    private BigDecimal amount;
    private String reason;
    private String status;
    private Long applyBy;
    private Long approveBy;
    private Long batchId;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    /**
     * 员工姓名（非入库字段；序列化时按 {@link #employeeId} 从员工档案表翻译）。
     * 业务角色无 system:user:query 权限，前端不查员工全量表，由后端统一翻译。
     */
    @TableField(exist = false)
    @Translation(type = PanjiaTransConstant.EMPLOYEE_ID_TO_NAME, mapper = "employeeId")
    private String employeeName;
}
