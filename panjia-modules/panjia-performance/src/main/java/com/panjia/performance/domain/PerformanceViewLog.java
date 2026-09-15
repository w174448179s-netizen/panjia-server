package com.panjia.performance.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 业绩查看留痕（对应 pj_perf_view_log 表）。
 * <p>
 * 经纪人每次打开含他人业绩的合同必须留痕（§3.6），
 * 用于防批量爬业绩 + 争议时有据可查。
 * <p>店长/总监/算薪属职权查看不记，避免噪声。
 * <p>仅只读，不提供删除接口。
 */
@Data
@TableName("pj_perf_view_log")
public class PerformanceViewLog implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 雪花 ID */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 被查看合同 ID（若合同域有显式实体） */
    private Long contractId;

    /** 被查看合同号（冗余，便于检索） */
    private String contractNo;

    /** 查看人员工 ID（仅经纪人记） */
    private Long viewerEmployeeId;

    /** 被查看的角色人集合（CSV，本次可见的他人） */
    private String viewedEmployeeIds;

    /** 查看时间 */
    private LocalDateTime viewTime;

    /** 进入来源（如 MY_PERF_DRILLDOWN 我的业绩下钻） */
    private String source;
}
