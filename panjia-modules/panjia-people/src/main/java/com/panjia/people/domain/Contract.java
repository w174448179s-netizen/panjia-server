package com.panjia.people.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 员工合同（对应 pj_people_contract 表）。
 * <p>
 * 劳动合同/兼职合同/其他；续签 = 新增记录，历史保留。
 * 身份证扫描件存 sys_oss.ossId 串（逗号分隔），文件本体走 OSS。
 */
@Data
@TableName("pj_people_contract")
public class Contract implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 合同 ID，雪花 ID */
    @TableId(type = IdType.ASSIGN_ID)
    private Long contractId;

    /** 员工 ID */
    private Long employeeId;

    /** 合同类型：LABOR/PARTTIME/OTHER（字典 panjia_contract_type） */
    private String contractType;

    /** 签约门店（部门 ID） */
    private Long deptId;

    /** 合同开始日 */
    private LocalDate startDate;

    /** 合同结束日（null=无固定期限） */
    private LocalDate endDate;

    /** 身份证扫描件 ossId 串（逗号分隔） */
    private String idCardOssIds;

    /** 合同签署扫描件 ossId 串（逗号分隔），签订后上传留底 */
    private String contractOssIds;

    /** 备注 */
    private String remark;

    /** 创建时间（DB 默认填充） */
    private LocalDateTime createTime;

    /** 更新时间（DB 默认填充） */
    private LocalDateTime updateTime;
}
