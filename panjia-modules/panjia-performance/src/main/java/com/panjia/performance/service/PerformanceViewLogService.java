package com.panjia.performance.service;

import com.panjia.performance.domain.PerformanceViewLog;
import com.panjia.performance.mapper.PerformanceViewLogMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 业绩查看留痕服务（§3.6）。
 * <p>
 * 经纪人每次打开含他人业绩的合同必须留痕；仅看自己那行不记。
 * <p>店长/总监/算薪属职权查看不记（避免噪声），调用方需自行过滤。
 * <p>异步 + REQUIRES_NEW：不影响主请求事务与响应时延。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PerformanceViewLogService {

    private final PerformanceViewLogMapper viewLogMapper;

    /**
     * 异步写入查看留痕。仅当 viewedEmployeeIds 非空（即含他人业绩）时实际写入。
     *
     * @param contractId         被查看合同 ID（可空）
     * @param contractNo         被查看合同号（可空，与 contractId 至少传一）
     * @param viewerEmployeeId   查看人员工 ID（经纪人）
     * @param viewedEmployeeIds  本次可见的他人角色人 ID 集合（含本人则剔除）
     * @param source             进入来源（如 MY_PERF_DRILLDOWN）
     */
    @Async
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void recordViewAsync(Long contractId, String contractNo, Long viewerEmployeeId,
                                 List<Long> viewedEmployeeIds, String source) {
        try {
            if (viewerEmployeeId == null || (contractId == null && contractNo == null)) {
                return;
            }
            // 仅记含他人业绩的打开：剔除本人后若空则不记
            List<Long> others = viewedEmployeeIds == null ? List.of()
                : viewedEmployeeIds.stream()
                    .filter(id -> id != null && !id.equals(viewerEmployeeId))
                    .toList();
            if (others.isEmpty()) {
                return;
            }
            PerformanceViewLog log = new PerformanceViewLog();
            log.setContractId(contractId);
            log.setContractNo(contractNo);
            log.setViewerEmployeeId(viewerEmployeeId);
            log.setViewedEmployeeIds(others.stream().map(String::valueOf)
                .collect(Collectors.joining(",")));
            log.setViewTime(LocalDateTime.now());
            log.setSource(source);
            viewLogMapper.insert(log);
        } catch (Exception e) {
            // 留痕失败不影响主流程，仅记日志
            PerformanceViewLogService.log.error("[查看留痕] 写入失败 contractNo={}, viewer={}",
                contractNo, viewerEmployeeId, e);
        }
    }
}
