-- =============================================================================
-- 修复脚本：新签调整实收联动“订单号=合同号”误判产生的多余明细
-- 生成日期：2026-11-07
--
-- 涉及合同：
--   100015671805 / 订单号 14567723 / 实收壳 2117528997824548865 / 审批单 RCV20261103160710115
--   100015719408 / 订单号 14711693 / 实收壳 2117528997845520386 / 审批单 RCV20261103160710121
--
-- 问题：新签事实 order_no 被误填成合同号，调整联动按「订单号+合同号」查实收 0 命中，
--       走“合同无实收”分支补建了 19 行孤儿明细（received_apply_id 为空、source_key 含 MANUAL-RADJ），
--       同时原实收明细未做 supersede，导致明细翻倍、镜像单金额为 0。
--
-- 修复内容：
--   1) 删除 19 行误建孤儿明细
--   2) 按系统 supersede 语义（旧行 REVERSED + 新行 ACTIVE，同 source_key）把 10 行原明细替换为目标金额
--   3) 重算 2 张实收合同的 item_count / period_total_received
--   4) 修正 3 张“实收调整”镜像单的 original_amount / target_amount
--
-- 前置条件：代码修复（ReceivedRealFactAdapter.findActiveByContract 去掉 on.equals(cn) 短路）已发布
-- 执行方式：
--   sudo docker cp repair_received_adjust_orphan_20261107.sql panjia-postgres:/tmp/
--   sudo docker exec -i panjia-postgres psql -U panjia -d panjia -v ON_ERROR_STOP=1 \
--        -f /tmp/repair_received_adjust_orphan_20261107.sql
--
-- 特性：单事务；前置校验不通过直接异常；执行前自动备份；执行后自校验，失败自动回滚。
-- 建议：先在测试库完整跑一遍并对账，再生产执行。
-- =============================================================================

BEGIN;

-- -----------------------------------------------------------------------------
-- 1. 备份（表名固定，便于回滚；DDL 在事务内，失败会一并回滚）
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS pj_received_detail_bak_20261107 AS
SELECT * FROM pj_received_detail
WHERE contract_id IN (2117528997824548865, 2117528997845520386);

CREATE TABLE IF NOT EXISTS pj_received_contract_bak_20261107 AS
SELECT * FROM pj_received_contract
WHERE id IN (2117528997824548865, 2117528997845520386);

CREATE TABLE IF NOT EXISTS pj_perf_received_adjust_bak_20261107 AS
SELECT * FROM pj_perf_received_adjust
WHERE id IN (2117988517605638146, 2118516698060201985, 2117988533711765506);

-- -----------------------------------------------------------------------------
-- 2. 前置校验（数据不符合预期则终止）
-- -----------------------------------------------------------------------------
DO $$
DECLARE
    orphan_cnt int;
    original_ok_cnt int;
    unchanged_cnt int;
BEGIN
    -- 2.1 孤儿明细必须正好 19 行
    SELECT count(*) INTO orphan_cnt
    FROM pj_received_detail
    WHERE contract_id IN (2117528997824548865, 2117528997845520386)
      AND received_apply_id IS NULL
      AND adjust_id IS NOT NULL
      AND detail_status = 'ACTIVE'
      AND source_key LIKE '%|MANUAL-RADJ-%';
    IF orphan_cnt <> 19 THEN
        RAISE EXCEPTION '前置校验失败：期望19行孤儿明细，实际%行', orphan_cnt;
    END IF;

    -- 2.2 10 行待替换原明细必须仍是 ACTIVE 且金额等于调整前原值
    SELECT count(*) INTO original_ok_cnt
    FROM (VALUES
        (2117528997824548866::bigint, 301.77::numeric),
        (2117528997828743170::bigint,  60.35::numeric),
        (2117528997824548867::bigint, 1508.79::numeric),
        (2117528997828743172::bigint, 301.77::numeric),
        (2117528997828743173::bigint, 301.77::numeric),
        (2117528997832937473::bigint, 150.89::numeric),
        (2117528997832937474::bigint,  90.53::numeric),
        (2117528997845520387::bigint, 1547.00::numeric),
        (2117528997845520388::bigint, 9999.16::numeric),
        (2117528997849714689::bigint, 9199.23::numeric)
    ) v(id, old_amount)
    JOIN pj_received_detail d ON d.id = v.id
    WHERE d.detail_status = 'ACTIVE'
      AND d.performance_amount = v.old_amount;
    IF original_ok_cnt <> 10 THEN
        RAISE EXCEPTION '前置校验失败：期望10行待替换原明细，实际匹配%行', original_ok_cnt;
    END IF;

    -- 2.3 未受影响的 1 行（毕凤树·客源成交人）必须保持 ACTIVE 211.24
    SELECT count(*) INTO unchanged_cnt
    FROM pj_received_detail
    WHERE id = 2117528997828743171
      AND detail_status = 'ACTIVE'
      AND performance_amount = 211.24;
    IF unchanged_cnt <> 1 THEN
        RAISE EXCEPTION '前置校验失败：未受影响行 2117528997828743171 状态/金额异常';
    END IF;
END $$;

-- -----------------------------------------------------------------------------
-- 3. 删除误建孤儿明细（19 行）
-- -----------------------------------------------------------------------------
DELETE FROM pj_received_detail
WHERE contract_id IN (2117528997824548865, 2117528997845520386)
  AND received_apply_id IS NULL
  AND adjust_id IS NOT NULL
  AND detail_status = 'ACTIVE'
  AND source_key LIKE '%|MANUAL-RADJ-%';

-- -----------------------------------------------------------------------------
-- 4. 原明细按 supersede 状态机转 REVERSED（expire_date = 生效日 - 1 天）
-- -----------------------------------------------------------------------------
UPDATE pj_received_detail d
SET detail_status = 'REVERSED',
    reversal_type = 'SUPERSEDE',
    expire_date = d.effective_date - 1,
    operator_id = 2113796258562994177,
    update_time = now()
FROM (VALUES
    (2117528997824548866::bigint),
    (2117528997828743170::bigint),
    (2117528997824548867::bigint),
    (2117528997828743172::bigint),
    (2117528997828743173::bigint),
    (2117528997832937473::bigint),
    (2117528997832937474::bigint),
    (2117528997845520387::bigint),
    (2117528997845520388::bigint),
    (2117528997849714689::bigint)
) v(id)
WHERE d.id = v.id
  AND d.detail_status = 'ACTIVE';

-- -----------------------------------------------------------------------------
-- 5. 插入目标金额的新 ACTIVE 行（复制原行业务字段；source_key 不变；
--    金额 = 原值 + 联动 delta；adjust_id 记录实际生效的那张调整单；
--    id 取 10^18 段，低于雪花号段，避免与 MyBatis-Plus 未来雪花 id 冲突）
-- -----------------------------------------------------------------------------
INSERT INTO pj_received_detail (
    id, contract_id, employee_id, employee_external_code, role_type, role_name,
    share_ratio, performance_amount, fee_item, period, effective_date, expire_date,
    source_key, source_batch_id, normalized_record_id, received_apply_id, detail_status,
    operator_id, adjust_id, reversal_type, refund_of_detail_id, dept_id,
    superseded_by_batch_id, supplement_origin_batch_id, create_time, update_time
)
SELECT
    1000000000000000000::bigint + row_number() OVER (ORDER BY d.id) AS id,
    d.contract_id,
    d.employee_id,
    d.employee_external_code,
    d.role_type,
    d.role_name,
    d.share_ratio,
    m.new_amount,
    d.fee_item,
    d.period,
    d.effective_date,
    NULL,
    d.source_key,
    d.source_batch_id,
    d.normalized_record_id,
    d.received_apply_id,
    'ACTIVE',
    d.operator_id,
    m.adjust_id,
    NULL,
    d.refund_of_detail_id,
    d.dept_id,
    d.superseded_by_batch_id,
    d.supplement_origin_batch_id,
    now(),
    now()
FROM pj_received_detail d
JOIN (VALUES
    -- 100015671805：龙新春 -282.70 → 19.07
    (2117528997828743172::bigint,   19.07::numeric, 2117988364052168705::bigint),
    -- 100015671805：李建华 -1413.54 → 95.25
    (2117528997824548867::bigint,   95.25::numeric, 2118516626471821314::bigint),
    -- 100015671805：张彬·房源维护人 -282.70 → 19.07
    (2117528997828743173::bigint,   19.07::numeric, 2117988364052168705::bigint),
    -- 100015671805：张彬·考核陪签 -141.34 → 9.55
    (2117528997832937473::bigint,    9.55::numeric, 2118516626471821314::bigint),
    -- 100015671805：张彬·信息完备 -84.81 → 5.72
    (2117528997832937474::bigint,    5.72::numeric, 2117988364052168705::bigint),
    -- 100015671805：毕凤树·房源录入人 +1554.88 → 1856.65
    (2117528997824548866::bigint, 1856.65::numeric, 2118516626471821314::bigint),
    -- 100015671805：毕凤树·房源委托书人 +650.21 → 710.56
    (2117528997828743170::bigint,  710.56::numeric, 2117988364052168705::bigint),
    -- 100015719408：邓阳·客源成交人 -3835.00 → 5364.23
    (2117528997849714689::bigint, 5364.23::numeric, 2117988189309075457::bigint),
    -- 100015719408：邓阳·客源成交人|贷款服务费 -1445.00 → 102.00
    (2117528997845520387::bigint,  102.00::numeric, 2117988189309075457::bigint),
    -- 100015719408：张虹林·客源合作人 -5723.00 → 4276.16
    (2117528997845520388::bigint, 4276.16::numeric, 2117988189309075457::bigint)
) m(id, new_amount, adjust_id) ON m.id = d.id;

-- -----------------------------------------------------------------------------
-- 6. 重算实收合同聚合（item_count / period_total_received）
-- -----------------------------------------------------------------------------
UPDATE pj_received_contract c
SET item_count = agg.cnt,
    period_total_received = agg.total,
    update_time = now()
FROM (
    SELECT contract_id,
           count(*) AS cnt,
           coalesce(sum(performance_amount), 0) AS total
    FROM pj_received_detail
    WHERE contract_id IN (2117528997824548865, 2117528997845520386)
      AND detail_status = 'ACTIVE'
    GROUP BY contract_id
) agg
WHERE c.id = agg.contract_id;

-- -----------------------------------------------------------------------------
-- 7. 修正实收调整镜像单金额
--   100015671805 两张：实收合计调整前后均为 2927.11
--   100015719408 一张：20745.39 → 9742.39
-- -----------------------------------------------------------------------------
UPDATE pj_perf_received_adjust
SET original_amount = 2927.11,
    target_amount = 2927.11,
    update_time = now()
WHERE id IN (2117988517605638146, 2118516698060201985);

UPDATE pj_perf_received_adjust
SET original_amount = 20745.39,
    target_amount = 9742.39,
    update_time = now()
WHERE id = 2117988533711765506;

-- -----------------------------------------------------------------------------
-- 8. 执行后自校验
-- -----------------------------------------------------------------------------
DO $$
DECLARE
    a_cnt int;
    a_total numeric;
    b_cnt int;
    b_total numeric;
    orphan_left int;
    active_null_apply int;
    a_item_count int;
    a_contract_total numeric;
    b_item_count int;
    b_contract_total numeric;
BEGIN
    -- 8.1 100015671805：ACTIVE 8 行、合计 2927.11
    SELECT count(*), coalesce(sum(performance_amount), 0)
      INTO a_cnt, a_total
    FROM pj_received_detail
    WHERE contract_id = 2117528997824548865 AND detail_status = 'ACTIVE';
    IF a_cnt <> 8 OR a_total <> 2927.11 THEN
        RAISE EXCEPTION '合同100015671805校验失败：ACTIVE行数=%，合计=%', a_cnt, a_total;
    END IF;

    -- 8.2 100015719408：ACTIVE 3 行、合计 9742.39
    SELECT count(*), coalesce(sum(performance_amount), 0)
      INTO b_cnt, b_total
    FROM pj_received_detail
    WHERE contract_id = 2117528997845520386 AND detail_status = 'ACTIVE';
    IF b_cnt <> 3 OR b_total <> 9742.39 THEN
        RAISE EXCEPTION '合同100015719408校验失败：ACTIVE行数=%，合计=%', b_cnt, b_total;
    END IF;

    -- 8.3 不得残留孤儿 ACTIVE 行
    SELECT count(*) INTO orphan_left
    FROM pj_received_detail
    WHERE contract_id IN (2117528997824548865, 2117528997845520386)
      AND received_apply_id IS NULL
      AND detail_status = 'ACTIVE';
    IF orphan_left <> 0 THEN
        RAISE EXCEPTION '校验失败：仍有%行未绑定审批单的ACTIVE明细', orphan_left;
    END IF;

    -- 8.4 合同聚合与明细一致
    SELECT item_count, period_total_received
      INTO a_item_count, a_contract_total
    FROM pj_received_contract WHERE id = 2117528997824548865;
    SELECT item_count, period_total_received
      INTO b_item_count, b_contract_total
    FROM pj_received_contract WHERE id = 2117528997845520386;
    IF a_item_count <> 8 OR a_contract_total <> 2927.11 THEN
        RAISE EXCEPTION '合同100015671805聚合校验失败：item_count=%，total=%', a_item_count, a_contract_total;
    END IF;
    IF b_item_count <> 3 OR b_contract_total <> 9742.39 THEN
        RAISE EXCEPTION '合同100015719408聚合校验失败：item_count=%，total=%', b_item_count, b_contract_total;
    END IF;

    RAISE NOTICE '修复完成：100015671805(8行/2927.11)、100015719408(3行/9742.39)';
END $$;

COMMIT;

-- =============================================================================
-- 回滚（仅在执行后发现异常时手工执行；备份表保留建议 7 天后清理）
-- BEGIN;
-- -- 1) 删除本次新插入的 10 行（id 在 10^18 段）
-- DELETE FROM pj_received_detail
-- WHERE id BETWEEN 1000000000000000001 AND 1000000000000000010;
-- -- 2) 恢复被 supersede 的原行状态
-- UPDATE pj_received_detail d
-- SET detail_status = b.detail_status, reversal_type = b.reversal_type,
--     expire_date = b.expire_date, update_time = b.update_time
-- FROM pj_received_detail_bak_20261107 b
-- WHERE d.id = b.id;
-- -- 3) 恢复被删除的 19 行孤儿明细
-- INSERT INTO pj_received_detail
-- SELECT * FROM pj_received_detail_bak_20261107 b
-- WHERE b.received_apply_id IS NULL AND b.adjust_id IS NOT NULL
--   AND b.source_key LIKE '%|MANUAL-RADJ-%'
--   AND NOT EXISTS (SELECT 1 FROM pj_received_detail x WHERE x.id = b.id);
-- -- 4) 恢复合同聚合
-- UPDATE pj_received_contract c
-- SET item_count = b.item_count, period_total_received = b.period_total_received,
--     update_time = b.update_time
-- FROM pj_received_contract_bak_20261107 b
-- WHERE c.id = b.id;
-- -- 5) 恢复镜像单
-- UPDATE pj_perf_received_adjust a
-- SET original_amount = b.original_amount, target_amount = b.target_amount,
--     update_time = b.update_time
-- FROM pj_perf_received_adjust_bak_20261107 b
-- WHERE a.id = b.id;
-- COMMIT;
-- =============================================================================
