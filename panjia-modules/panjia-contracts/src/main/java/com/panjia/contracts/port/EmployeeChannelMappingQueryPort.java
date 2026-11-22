package com.panjia.contracts.port;

import com.panjia.contracts.dto.EmployeeChannelMappingDTO;

import java.time.YearMonth;
import java.util.List;

/**
 * 员工-渠道账号映射查询端口（people 域实现，渠道同步模块消费）。
 * <p>
 * 钉钉通讯录 {@code /topapi/v2/user/list} 不返回已离职员工，导致考勤/积分
 * 同步的「userId → 工号」名单漏掉离职员工，其离职当月数据无法拉取。
 * 本端口提供「离职当月员工」的渠道映射，供同步模块兜底补全名单。
 */
public interface EmployeeChannelMappingQueryPort {

    /**
     * 查离职日期落在指定月份的员工的渠道账号映射。
     *
     * @param channel 渠道标识（DINGTALK / WECOM / FEISHU）
     * @param corpId  企业标识（钉钉 corpId，可为空串）
     * @param month   离职月份
     * @return 离职当月员工的（工号, 渠道 userId）列表；无则空列表
     */
    List<EmployeeChannelMappingDTO> listDimissionInMonth(String channel, String corpId, YearMonth month);
}
