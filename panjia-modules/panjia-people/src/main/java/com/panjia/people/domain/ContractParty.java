package com.panjia.people.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 合同甲方（用人单位）配置（对应 pj_people_contract_party 表）。
 * <p>
 * 部门 → 甲方三要素（单位名/注册地址/法定代表人）映射；
 * dept_id=0 为默认甲方，部门未配置映射时兜底。
 */
@Data
@TableName("pj_people_contract_party")
public class ContractParty implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 主键，雪花 ID */
    @TableId(type = IdType.ASSIGN_ID)
    private Long partyId;

    /** 映射部门 ID（0=默认甲方） */
    private Long deptId;

    /** 单位（甲方）全称 */
    private String companyName;

    /** 注册地址 */
    private String regAddress;

    /** 法定代表人 */
    private String legalPerson;

    /** 备注 */
    private String remark;

    /** 创建人 */
    private Long createBy;

    /** 创建时间（DB 默认填充） */
    private LocalDateTime createTime;

    /** 更新人 */
    private Long updateBy;

    /** 更新时间 */
    private LocalDateTime updateTime;
}
