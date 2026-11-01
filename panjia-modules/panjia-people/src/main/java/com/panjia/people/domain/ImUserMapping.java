package com.panjia.people.domain;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * IM 账号映射（对应 pj_people_im_mapping 表）。
 * <p>
 * 一条记录 = 某渠道（钉钉/企微/飞书）的某个账号与本地员工/登录账号的映射。
 * 渠道无关：用 channel + corpId 区分渠道与企业，渠道差异字段放 extJson。
 */
@Data
@TableName("pj_people_im_mapping")
public class ImUserMapping implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 映射 ID，雪花 ID */
    @TableId(type = IdType.ASSIGN_ID)
    private Long mappingId;

    /** 渠道：DINGTALK/WECOM/FEISHU */
    private String channel;

    /** 企业标识（钉钉 corpId / 企微 corpid；单企业为空串） */
    private String corpId;

    /** 渠道内用户唯一标识（钉钉 userid 等） */
    private String externalUserId;

    /** 开放平台 unionid，可空 */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String externalUnionId;

    /** 渠道侧姓名 */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String externalName;

    /** 渠道侧手机号 */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String externalMobile;

    /** 渠道侧工号 */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String externalCode;

    /** 渠道侧是否在职 */
    private Boolean externalActive;

    /** 本地员工 ID，未匹配为空（重同步时需允许置空） */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Long employeeId;

    /** 本地登录账号 ID，未开户为空（重同步时需允许置空） */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Long userId;

    /** 匹配方式：CODE/MOBILE/MANUAL/NONE */
    private String matchType;

    /** 渠道差异扩展字段（JSON 文本，预留） */
    private String extJson;

    /** 最近一次同步时间 */
    private LocalDateTime lastSyncTime;

    /** 创建时间（DB 默认填充） */
    private LocalDateTime createTime;

    /** 更新时间（DB 默认填充） */
    private LocalDateTime updateTime;
}
