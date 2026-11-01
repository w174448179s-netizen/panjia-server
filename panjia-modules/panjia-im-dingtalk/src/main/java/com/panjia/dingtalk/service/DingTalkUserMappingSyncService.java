package com.panjia.dingtalk.service;

import com.panjia.contracts.dto.ImUserMappingSyncResult;
import com.panjia.dingtalk.domain.DingTalkSyncLog;
import com.panjia.dingtalk.dto.DingTalkUserDTO;

import java.util.List;

/**
 * 钉钉通讯录 → IM 账号映射同步服务。
 */
public interface DingTalkUserMappingSyncService {

    /**
     * 手动同步：拉钉钉通讯录并写入 IM 账号映射表（异步执行，立即返回 RUNNING 记录）。
     *
     * @param operatorId 操作人 ID（可为空）
     * @return 同步日志
     */
    DingTalkSyncLog startAsync(Long operatorId);

    /**
     * 用已拉取的通讯录刷新映射（供考勤/积分同步复用，避免重复请求钉钉接口）。
     *
     * @param users 钉钉通讯录人员
     * @return 映射同步统计
     */
    ImUserMappingSyncResult syncUsers(List<DingTalkUserDTO> users);
}
