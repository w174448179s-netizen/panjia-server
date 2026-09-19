package com.panjia.people.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.panjia.contracts.dto.AttendanceApprovalStatusDTO;
import com.panjia.people.domain.AttendanceApproval;
import com.panjia.people.dto.AttendanceApprovalVO;
import com.panjia.people.mapper.AttendanceApprovalMapper;
import com.panjia.people.mapper.AttendanceRecordMapper;
import com.panjia.people.service.AttendanceApprovalService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;

/**
 * 考勤审批服务实现。
 * <p>
 * 状态机：DRAFT → SUBMITTED → APPROVED / REJECTED（驳回后可重新提交）。
 * 考勤重新导入时数据被覆盖，SUBMITTED/APPROVED 单据自动失效回 DRAFT，
 * 防止"审批通过后偷偷改数仍按旧审批算薪"。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AttendanceApprovalServiceImpl implements AttendanceApprovalService {

    private final AttendanceApprovalMapper approvalMapper;
    private final AttendanceRecordMapper attendanceMapper;

    @Override
    public AttendanceApprovalVO getByPeriod(String period) {
        AttendanceApproval entity = selectByPeriod(period);
        AttendanceApprovalVO vo = new AttendanceApprovalVO();
        vo.setPeriod(period);
        if (entity == null) {
            return vo;
        }
        vo.setId(entity.getId());
        vo.setStatus(entity.getStatus());
        vo.setSubmitBy(entity.getSubmitBy());
        vo.setSubmitTime(entity.getSubmitTime());
        vo.setApproveBy(entity.getApproveBy());
        vo.setApproveTime(entity.getApproveTime());
        vo.setRejectReason(entity.getRejectReason());
        return vo;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void submit(String period, Long operatorId) {
        validatePeriod(period);
        YearMonth month = YearMonth.parse(period.trim());
        long dataCount = attendanceMapper.selectCount(new LambdaQueryWrapper<com.panjia.people.domain.AttendanceRecord>()
            .eq(com.panjia.people.domain.AttendanceRecord::getAttendMonth, month.atDay(1)));
        if (dataCount == 0) {
            throw new ServiceException("该期间无考勤数据，无法提交审批");
        }
        AttendanceApproval entity = selectByPeriod(period);
        if (entity == null) {
            entity = new AttendanceApproval();
            entity.setPeriod(period);
            entity.setStatus(AttendanceApproval.STATUS_DRAFT);
            approvalMapper.insert(entity);
        }
        if (AttendanceApproval.STATUS_SUBMITTED.equals(entity.getStatus())) {
            throw new ServiceException("该期间考勤已提交，等待总监审批");
        }
        if (AttendanceApproval.STATUS_APPROVED.equals(entity.getStatus())) {
            throw new ServiceException("该期间考勤已审批通过，无需重复提交");
        }
        entity.setStatus(AttendanceApproval.STATUS_SUBMITTED);
        entity.setSubmitBy(operatorId);
        entity.setSubmitTime(LocalDateTime.now());
        entity.setApproveBy(null);
        entity.setApproveTime(null);
        entity.setRejectReason(null);
        if (approvalMapper.updateById(entity) == 0) {
            throw new ServiceException("提交失败，审批单已被他人操作，请刷新后重试");
        }
        log.info("[考勤审批] 提交审批：period={}, operatorId={}", period, operatorId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void approve(Long id, Long operatorId) {
        AttendanceApproval entity = requireById(id);
        if (!AttendanceApproval.STATUS_SUBMITTED.equals(entity.getStatus())) {
            throw new ServiceException("仅待审批状态可审批通过");
        }
        entity.setStatus(AttendanceApproval.STATUS_APPROVED);
        entity.setApproveBy(operatorId);
        entity.setApproveTime(LocalDateTime.now());
        if (approvalMapper.updateById(entity) == 0) {
            throw new ServiceException("审批失败，单据已被他人操作，请刷新后重试");
        }
        log.info("[考勤审批] 审批通过：period={}, operatorId={}", entity.getPeriod(), operatorId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void reject(Long id, String reason, Long operatorId) {
        if (StringUtils.isBlank(reason)) {
            throw new ServiceException("驳回原因不能为空");
        }
        AttendanceApproval entity = requireById(id);
        if (!AttendanceApproval.STATUS_SUBMITTED.equals(entity.getStatus())) {
            throw new ServiceException("仅待审批状态可驳回");
        }
        entity.setStatus(AttendanceApproval.STATUS_REJECTED);
        entity.setApproveBy(operatorId);
        entity.setApproveTime(LocalDateTime.now());
        entity.setRejectReason(reason.trim());
        if (approvalMapper.updateById(entity) == 0) {
            throw new ServiceException("驳回失败，单据已被他人操作，请刷新后重试");
        }
        log.info("[考勤审批] 驳回：period={}, operatorId={}, reason={}", entity.getPeriod(), operatorId, reason);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void invalidateOnDataChange(String period) {
        AttendanceApproval entity = selectByPeriod(period);
        if (entity == null) {
            return;
        }
        String status = entity.getStatus();
        if (AttendanceApproval.STATUS_SUBMITTED.equals(status)
            || AttendanceApproval.STATUS_APPROVED.equals(status)) {
            entity.setStatus(AttendanceApproval.STATUS_DRAFT);
            if (approvalMapper.updateById(entity) == 0) {
                log.warn("[考勤审批] 失效审批单失败（并发修改）：period={}", period);
                return;
            }
            log.info("[考勤审批] 考勤数据变更，审批单失效回待提交：period={}，原状态={}", period, status);
        }
    }

    // ==================== 导入同步卡点查询（PeopleAttendanceApprovalQueryPort） ====================

    @Override
    public AttendanceApprovalStatusDTO getApprovalStatus(String period) {
        AttendanceApprovalStatusDTO dto = new AttendanceApprovalStatusDTO();
        dto.setPeriod(period);
        boolean dataExists;
        try {
            YearMonth month = YearMonth.parse(period.trim());
            dataExists = attendanceMapper.selectCount(new LambdaQueryWrapper<com.panjia.people.domain.AttendanceRecord>()
                .eq(com.panjia.people.domain.AttendanceRecord::getAttendMonth, month.atDay(1))) > 0;
        } catch (DateTimeParseException e) {
            dataExists = false;
        }
        dto.setDataExists(dataExists);
        AttendanceApproval entity = selectByPeriod(period);
        dto.setStatus(entity == null ? null : entity.getStatus());
        return dto;
    }

    // ==================== 内部方法 ====================

    private AttendanceApproval selectByPeriod(String period) {
        return approvalMapper.selectOne(new LambdaQueryWrapper<AttendanceApproval>()
            .eq(AttendanceApproval::getPeriod, period)
            .last("LIMIT 1"));
    }

    private AttendanceApproval requireById(Long id) {
        AttendanceApproval entity = approvalMapper.selectById(id);
        if (entity == null) {
            throw new ServiceException("审批单不存在，id={}", id);
        }
        return entity;
    }

    /** 期间格式校验（YYYY-MM） */
    private void validatePeriod(String period) {
        if (StringUtils.isBlank(period)) {
            throw new ServiceException("归属期间不能为空");
        }
        try {
            YearMonth.parse(period.trim());
        } catch (DateTimeParseException e) {
            throw new ServiceException("归属期间格式不正确，应为 YYYY-MM：{}", period);
        }
    }
}
