package com.panjia.ledger.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 理房通门店对公账户配置。
 * <p>文件名前缀唯一，导入时按前缀识别门店和账户。</p>
 */
@Data
@TableName("pj_lf_account")
public class LfAccount implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 门店部门 ID（sys_dept） */
    private Long storeDeptId;

    private String storeName;

    /** 导出文件名前缀 */
    private String filePrefix;

    /** 对公账户名称 */
    private String accountName;

    /** 0=启用 / 1=停用 */
    private String status;

    private String remark;

    private Long createBy;
    private LocalDateTime createTime;
    private Long updateBy;
    private LocalDateTime updateTime;
}
