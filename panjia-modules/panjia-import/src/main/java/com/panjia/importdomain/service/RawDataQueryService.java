package com.panjia.importdomain.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.panjia.importdomain.domain.ImportSourceType;
import com.panjia.importdomain.domain.raw.RawData;
import com.panjia.importdomain.mapper.RawAttendanceMapper;
import com.panjia.importdomain.mapper.RawManualMapper;
import com.panjia.importdomain.mapper.RawNewSignMapper;
import com.panjia.importdomain.mapper.RawPointsMapper;
import com.panjia.importdomain.mapper.RawSignedMapper;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.PageResult;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.Collections;

/**
 * 导入原始数据查询服务（V2.0 §3.2 / §11.1）。
 *
 * <p>RawData 是只读归档（insert-only），由前端「查看原始数据」功能消费，
 * 与归一化产物 {@code pj_normalized_record} 互补：审计/追溯时取原始行，
 * 算薪消费取归一化行。
 */
@Service
@RequiredArgsConstructor
public class RawDataQueryService {

    private final RawSignedMapper rawSignedMapper;
    private final RawNewSignMapper rawNewSignMapper;
    private final RawAttendanceMapper rawAttendanceMapper;
    private final RawPointsMapper rawPointsMapper;
    private final RawManualMapper rawManualMapper;

    /**
     * 按 (sourceType, batchId) 读取该批次全部原始行，按 row_no 升序分页返回。
     *
     * @param sourceType  数据源类型，决定读哪张 raw 分表
     * @param batchId     批次 ID
     * @param pageNum     1-based 页码
     * @param pageSize    页大小
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public PageResult<RawData> listRaw(ImportSourceType sourceType, Long batchId,
                                       Integer pageNum, Integer pageSize) {
        if (sourceType == null || batchId == null) {
            return PageResult.build(Collections.<RawData>emptyList(), 0L);
        }
        switch (sourceType) {
            case KE_SIGNED -> {
                LambdaQueryWrapper<com.panjia.importdomain.domain.raw.RawSigned> w = new LambdaQueryWrapper<>();
                w.eq(com.panjia.importdomain.domain.raw.RawSigned::getBatchId, batchId)
                    .orderByAsc(com.panjia.importdomain.domain.raw.RawSigned::getRowNo);
                Page<com.panjia.importdomain.domain.raw.RawSigned> p = rawSignedMapper.selectPage(
                    new Page<com.panjia.importdomain.domain.raw.RawSigned>(pageNum, pageSize), w);
                return PageResult.build((Collection) p.getRecords(), p.getTotal());
            }
            case KE_NEW_SIGN -> {
                LambdaQueryWrapper<com.panjia.importdomain.domain.raw.RawNewSign> w = new LambdaQueryWrapper<>();
                w.eq(com.panjia.importdomain.domain.raw.RawNewSign::getBatchId, batchId)
                    .orderByAsc(com.panjia.importdomain.domain.raw.RawNewSign::getRowNo);
                Page<com.panjia.importdomain.domain.raw.RawNewSign> p = rawNewSignMapper.selectPage(
                    new Page<com.panjia.importdomain.domain.raw.RawNewSign>(pageNum, pageSize), w);
                return PageResult.build((Collection) p.getRecords(), p.getTotal());
            }
            case ATTENDANCE -> {
                LambdaQueryWrapper<com.panjia.importdomain.domain.raw.RawAttendance> w = new LambdaQueryWrapper<>();
                w.eq(com.panjia.importdomain.domain.raw.RawAttendance::getBatchId, batchId)
                    .orderByAsc(com.panjia.importdomain.domain.raw.RawAttendance::getRowNo);
                Page<com.panjia.importdomain.domain.raw.RawAttendance> p = rawAttendanceMapper.selectPage(
                    new Page<com.panjia.importdomain.domain.raw.RawAttendance>(pageNum, pageSize), w);
                return PageResult.build((Collection) p.getRecords(), p.getTotal());
            }
            case POINTS -> {
                LambdaQueryWrapper<com.panjia.importdomain.domain.raw.RawPoints> w = new LambdaQueryWrapper<>();
                w.eq(com.panjia.importdomain.domain.raw.RawPoints::getBatchId, batchId)
                    .orderByAsc(com.panjia.importdomain.domain.raw.RawPoints::getRowNo);
                Page<com.panjia.importdomain.domain.raw.RawPoints> p = rawPointsMapper.selectPage(
                    new Page<com.panjia.importdomain.domain.raw.RawPoints>(pageNum, pageSize), w);
                return PageResult.build((Collection) p.getRecords(), p.getTotal());
            }
            case OTHERS -> {
                LambdaQueryWrapper<com.panjia.importdomain.domain.raw.RawManual> w = new LambdaQueryWrapper<>();
                w.eq(com.panjia.importdomain.domain.raw.RawManual::getBatchId, batchId)
                    .orderByAsc(com.panjia.importdomain.domain.raw.RawManual::getRowNo);
                Page<com.panjia.importdomain.domain.raw.RawManual> p = rawManualMapper.selectPage(
                    new Page<com.panjia.importdomain.domain.raw.RawManual>(pageNum, pageSize), w);
                return PageResult.build((Collection) p.getRecords(), p.getTotal());
            }
            default -> {
                return PageResult.build(Collections.<RawData>emptyList(), 0L);
            }
        }
    }
}
