package com.panjia.commission.runner;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.panjia.commission.domain.CommissionApplication;
import com.panjia.commission.domain.CommissionItem;
import com.panjia.commission.domain.ItemStatus;
import com.panjia.commission.mapper.CommissionApplicationMapper;
import com.panjia.commission.mapper.CommissionItemMapper;
import com.panjia.commission.service.CommissionApplicationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 历史结佣草稿单一次性迁移（2026-11-01 取消草稿态）。
 * <p>
 * 背景：早期实现「建单落 DRAFT、再提交」，提交失败或新签调整回退失败时会把单据留在草稿；
 * 草稿单既没有前端处理入口，其非冲销明细又占用新签事实（发起页因「事实已被结佣」而整行隐藏），
 * 造成用户「看不到、也处理不了」。草稿态已在 {@code ApplicationStatus} 中删除。
 * <p>
 * 迁移规则：仍有有效明细的草稿单 → 原单直接转为审批中并在该单上启动流程；
 * 无有效明细或启动流程失败的草稿单 → 作废并冲销明细（释放新签事实供重新发起）。
 * 全程使用原生 SQL 处理草稿状态：枚举值已删除，任何 MyBatis 读取都会因无法反序列化而失败。
 */
@Slf4j
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
@RequiredArgsConstructor
public class CommissionLegacyDraftMigrationRunner implements ApplicationRunner {

    private final JdbcTemplate jdbcTemplate;
    private final CommissionApplicationMapper applicationMapper;
    private final CommissionItemMapper itemMapper;
    private final CommissionApplicationService applicationService;

    @Override
    public void run(ApplicationArguments args) {
        List<Long> draftIds = jdbcTemplate.queryForList(
            "SELECT id FROM pj_commission_application WHERE status = 'DRAFT' ORDER BY id", Long.class);
        if (draftIds.isEmpty()) {
            return;
        }
        log.info("[结佣-草稿迁移] 发现 {} 张历史草稿单，开始自动转审批中", draftIds.size());
        int submitted = 0;
        int voided = 0;
        for (Long id : draftIds) {
            try {
                if (submitOne(id)) {
                    submitted++;
                } else {
                    voidOne(id, "无有效明细");
                    voided++;
                }
            } catch (Exception e) {
                log.warn("[结佣-草稿迁移] 草稿单转审批中失败，改为作废释放业绩事实：applicationId={}, reason={}",
                    id, e.getMessage());
                voidOne(id, e.getMessage());
                voided++;
            }
        }
        log.info("[结佣-草稿迁移] 处理完成：转审批中 {} 张，作废 {} 张", submitted, voided);
    }

    /**
     * 把单张草稿单转为审批中。
     *
     * @return true=已转审批中；false=无有效明细，调用方应作废该单
     */
    private boolean submitOne(Long applicationId) {
        Long activeItems = itemMapper.selectCount(new LambdaQueryWrapper<CommissionItem>()
            .eq(CommissionItem::getApplicationId, applicationId)
            .ne(CommissionItem::getStatus, ItemStatus.REVERSED));
        if (activeItems == null || activeItems == 0) {
            return false;
        }
        // 先用原生 SQL 落 SUBMITTED 并清空流程字段：枚举已删除 DRAFT，MyBatis 无法读回该行；
        // 条件更新保证多实例启动时只有抢到该单的实例继续处理
        int claimed = jdbcTemplate.update(
            "UPDATE pj_commission_application SET status = 'SUBMITTED', process_instance_id = NULL, "
                + "current_node = NULL, approver_id = NULL, approve_time = NULL "
                + "WHERE id = ? AND status = 'DRAFT'", applicationId);
        if (claimed == 0) {
            return true;
        }
        CommissionApplication application = applicationMapper.selectById(applicationId);
        if (application == null) {
            return true;
        }
        Long operatorId = application.getApplicantId() != null ? application.getApplicantId() : 1L;
        applicationService.submit(application, operatorId, true,
            Boolean.TRUE.equals(application.getAutoInitiated()));
        log.info("[结佣-草稿迁移] 历史草稿单已转为审批中：applyNo={}, contractNo={}",
            application.getApplyNo(), application.getContractNo());
        return true;
    }

    /**
     * 作废并释放业绩事实（原生 SQL，避免枚举读取）。
     */
    private void voidOne(Long applicationId, String reason) {
        try {
            jdbcTemplate.update("UPDATE pj_commission_item SET status = 'REVERSED', reversed_reason = 'APPLICATION_CANCELLED' "
                + "WHERE application_id = ? AND status <> 'REVERSED'", applicationId);
            jdbcTemplate.update("UPDATE pj_commission_application SET status = 'CANCELLED', item_count = 0, "
                + "total_amount = 0, process_instance_id = NULL, current_node = NULL "
                + "WHERE id = ? AND status IN ('DRAFT', 'SUBMITTED')", applicationId);
            log.warn("[结佣-草稿迁移] 草稿单已作废并释放新签事实：applicationId={}, reason={}", applicationId, reason);
        } catch (Exception e) {
            log.error("[结佣-草稿迁移] 作废草稿单失败，需人工处理：applicationId={}, reason={}",
                applicationId, e.getMessage(), e);
        }
    }
}
