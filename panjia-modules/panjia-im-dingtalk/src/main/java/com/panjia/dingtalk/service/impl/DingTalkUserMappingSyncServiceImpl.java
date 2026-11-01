package com.panjia.dingtalk.service.impl;

import com.panjia.contracts.dto.ImUserMappingSyncResult;
import com.panjia.contracts.dto.ImUserSyncDTO;
import com.panjia.contracts.port.ImUserMappingSyncPort;
import com.panjia.dingtalk.client.DingTalkContactClient;
import com.panjia.dingtalk.config.DingTalkConfigProvider;
import com.panjia.dingtalk.domain.DingTalkSyncLog;
import com.panjia.dingtalk.dto.DingTalkUserDTO;
import com.panjia.dingtalk.service.DingTalkSyncLogService;
import com.panjia.dingtalk.service.DingTalkUserMappingSyncService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 钉钉通讯录 → IM 账号映射同步实现。
 * <p>
 * 只负责把钉钉人员转换成渠道无关的 {@link ImUserSyncDTO} 并交给员工域
 * {@link ImUserMappingSyncPort}；工号匹配与落库口径统一在员工域维护。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DingTalkUserMappingSyncServiceImpl implements DingTalkUserMappingSyncService {

    /** 渠道标识：钉钉 */
    private static final String CHANNEL_DINGTALK = "DINGTALK";

    private final DingTalkContactClient contactClient;
    private final DingTalkConfigProvider configProvider;
    private final ImUserMappingSyncPort mappingSyncPort;
    private final DingTalkSyncLogService syncLogService;

    /** 异步执行线程池（手动触发用，避免阻塞 HTTP 请求） */
    private final ExecutorService asyncExecutor = Executors.newFixedThreadPool(2, r -> {
        Thread thread = new Thread(r, "dingtalk-mapping-async");
        thread.setDaemon(true);
        return thread;
    });

    @Override
    public DingTalkSyncLog startAsync(Long operatorId) {
        if (!configProvider.enabled()) {
            throw new ServiceException("钉钉同步未启用，请在「参数管理」开启 panjia.dingtalk.enabled");
        }
        DingTalkSyncLog entity = syncLogService.start(
            DingTalkSyncLogService.TYPE_EMPLOYEE, null,
            DingTalkSyncLogService.TRIGGER_MANUAL, operatorId);
        asyncExecutor.execute(() -> {
            try {
                ImUserMappingSyncResult result = syncUsers(contactClient.listAllUsers());
                String status = result.unmatched() > 0
                    ? DingTalkSyncLogService.STATUS_PARTIAL : DingTalkSyncLogService.STATUS_SUCCESS;
                syncLogService.finish(entity, status, result.total(), result.matched(), result.unmatched(), 0,
                    String.format("钉钉人员 %d 人；匹配员工 %d 人；未匹配 %d 人；新增 %d 行 / 更新 %d 行",
                        result.total(), result.matched(), result.unmatched(), result.created(), result.updated()));
            } catch (Exception e) {
                // 异步线程内必须兜底，否则日志永远是 RUNNING
                log.error("[钉钉人员映射同步] 异步执行失败：{}", e.getMessage(), e);
                syncLogService.finish(entity, DingTalkSyncLogService.STATUS_FAILED, 0, 0, 0, 0,
                    "人员映射同步失败：" + e.getMessage());
            }
        });
        return entity;
    }

    @Override
    public ImUserMappingSyncResult syncUsers(List<DingTalkUserDTO> users) {
        List<ImUserSyncDTO> rows = new ArrayList<>(users.size());
        for (DingTalkUserDTO user : users) {
            ImUserSyncDTO row = new ImUserSyncDTO();
            row.setExternalUserId(user.getUserId());
            row.setExternalName(user.getName());
            row.setExternalMobile(user.getMobile());
            row.setExternalCode(user.getJobNumber());
            row.setExternalActive(user.getActive());
            rows.add(row);
        }
        return mappingSyncPort.sync(CHANNEL_DINGTALK, configProvider.corpId(), rows);
    }
}
