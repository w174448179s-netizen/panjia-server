#!/usr/bin/env bash
# ============================================================
# panjia 本地库业务数据清理脚本（应收 / 实收 / 结佣，含导入数据）
#
# 清理范围:
#   结佣  pj_commission_application / pj_commission_item / pj_commission_consume_log
#   实收  pj_received_contract / pj_received_detail / pj_perf_received_apply / pj_perf_received_adjust
#   应收  pj_perf_fact / pj_perf_adjust / pj_perf_refund / pj_perf_consume_log
#         pj_perf_view_log / pj_perf_period_close
#   导入  仅业绩/实收相关批次（KE_SIGNED / KE_RECEIVED / HISTORY_PAYROLL）
#         pj_import_raw_signed / pj_import_raw_received / pj_import_raw_payroll
#         pj_import_issue / pj_normalized_record / pj_import_batch
#   事件  相关 Outbox 事件及其幂等记录
#   流程  perf_adjust / perf_received / perf_received_adjust / perf_refund / commission_apply
#
# 不清理（基础信息 / 规则 / 其它域）:
#   人员、组织、合同、绩效方案与各项规则、导入模板、薪资、考勤、积分等
#
# 用法:
#   ./clean_business_data.sh        # 先打印待清理数量，交互确认后执行
#   ./clean_business_data.sh -y     # 跳过确认直接执行
#   ./clean_business_data.sh -n     # 只统计数量，不执行删除
#
# 可通过环境变量覆盖默认连接:
#   CONTAINER=postgres DB_NAME=postgres DB_USER=root DB_PASS=root ./clean_business_data.sh
# ============================================================
set -euo pipefail

# ---- 默认配置(环境变量可覆盖) ----
CONTAINER="${CONTAINER:-postgres}"
DB_NAME="${DB_NAME:-postgres}"
DB_USER="${DB_USER:-root}"
DB_PASS="${DB_PASS:-root}"

ASSUME_YES=0
DRY_RUN=0
while getopts "ynh" opt; do
  case "$opt" in
    y) ASSUME_YES=1 ;;
    n) DRY_RUN=1 ;;
    *) echo "用法: $0 [-y 跳过确认] [-n 只统计不删除]"; exit 1 ;;
  esac
done

# ---- 前置检查 ----
if ! docker ps --format '{{.Names}}' | grep -qx "$CONTAINER"; then
  echo "[错误] Docker 容器 [$CONTAINER] 未运行" >&2
  exit 1
fi

psql_cmd() {
  docker exec -e PGPASSWORD="$DB_PASS" "$CONTAINER" \
    psql -U "$DB_USER" -d "$DB_NAME" -v ON_ERROR_STOP=1 "$@"
}

print_counts() {
  psql_cmd -Atc "
    SELECT 'pj_commission_application', count(*) FROM pj_commission_application
    UNION ALL SELECT 'pj_commission_item', count(*) FROM pj_commission_item
    UNION ALL SELECT 'pj_commission_consume_log', count(*) FROM pj_commission_consume_log
    UNION ALL SELECT 'pj_received_contract', count(*) FROM pj_received_contract
    UNION ALL SELECT 'pj_received_detail', count(*) FROM pj_received_detail
    UNION ALL SELECT 'pj_perf_received_apply', count(*) FROM pj_perf_received_apply
    UNION ALL SELECT 'pj_perf_received_adjust', count(*) FROM pj_perf_received_adjust
    UNION ALL SELECT 'pj_perf_fact', count(*) FROM pj_perf_fact
    UNION ALL SELECT 'pj_perf_adjust', count(*) FROM pj_perf_adjust
    UNION ALL SELECT 'pj_perf_refund', count(*) FROM pj_perf_refund
    UNION ALL SELECT 'pj_perf_consume_log', count(*) FROM pj_perf_consume_log
    UNION ALL SELECT 'pj_perf_view_log', count(*) FROM pj_perf_view_log
    UNION ALL SELECT 'pj_perf_period_close', count(*) FROM pj_perf_period_close
    UNION ALL SELECT 'pj_import_batch(业绩相关)', count(*) FROM pj_import_batch
      WHERE source_type IN ('KE_SIGNED','KE_RECEIVED','HISTORY_PAYROLL')
    UNION ALL SELECT 'pj_import_raw_signed', count(*) FROM pj_import_raw_signed
    UNION ALL SELECT 'pj_import_raw_received', count(*) FROM pj_import_raw_received
    UNION ALL SELECT 'pj_import_raw_payroll', count(*) FROM pj_import_raw_payroll
    UNION ALL SELECT 'pj_import_issue(业绩相关)', count(*) FROM pj_import_issue
      WHERE batch_id IN (SELECT id FROM pj_import_batch
                         WHERE source_type IN ('KE_SIGNED','KE_RECEIVED','HISTORY_PAYROLL'))
    UNION ALL SELECT 'pj_normalized_record(业绩相关)', count(*) FROM pj_normalized_record
      WHERE batch_id IN (SELECT id FROM pj_import_batch
                         WHERE source_type IN ('KE_SIGNED','KE_RECEIVED','HISTORY_PAYROLL'))
    UNION ALL SELECT 'pj_event_outbox(业务事件)', count(*) FROM pj_event_outbox
      WHERE event_type IN ('import.batch.archived','import.batch.revoked','import.batch.renormalized',
                           'performance.fact.created','performance.fact.reversed',
                           'received.apply.approved','commission.approved','period.reopened')
    UNION ALL SELECT 'flow_instance(业务流程)', count(*) FROM flow_instance
      WHERE definition_id IN (SELECT id FROM flow_definition
                              WHERE flow_code IN ('perf_adjust','perf_received','perf_received_adjust',
                                                  'perf_refund','commission_apply'))
  " | awk -F'|' '{printf "  %-32s %s\n", $1, $2}'
}

echo "=========================================="
echo "  待清理数据统计（库: ${DB_NAME}）"
echo "=========================================="
print_counts
echo "=========================================="

if [ "$DRY_RUN" -eq 1 ]; then
  echo "[统计] 仅统计模式，未做任何修改"
  exit 0
fi

if [ "$ASSUME_YES" -ne 1 ]; then
  read -r -p "确认清理上述应收/实收/结佣数据? 输入 y 继续: " CONFIRM
  if [ "$CONFIRM" != "y" ]; then
    echo "[取消] 未做任何修改"
    exit 0
  fi
fi

echo "[清理] 开始执行（单事务，失败自动回滚）..."
docker exec -i -e PGPASSWORD="$DB_PASS" "$CONTAINER" \
  psql -U "$DB_USER" -d "$DB_NAME" -v ON_ERROR_STOP=1 <<'SQL'
BEGIN;

-- ---------------- 一、结佣 ----------------
DELETE FROM pj_commission_item;
DELETE FROM pj_commission_application;
DELETE FROM pj_commission_consume_log;

-- ---------------- 二、实收 ----------------
DELETE FROM pj_received_detail;
DELETE FROM pj_received_contract;
DELETE FROM pj_perf_received_apply;
DELETE FROM pj_perf_received_adjust;

-- ---------------- 三、应收（新签业绩） ----------------
DELETE FROM pj_perf_fact;
DELETE FROM pj_perf_adjust;
DELETE FROM pj_perf_refund;
DELETE FROM pj_perf_consume_log;
DELETE FROM pj_perf_view_log;
DELETE FROM pj_perf_period_close;

-- ---------------- 四、导入（仅业绩/实收相关批次） ----------------
DELETE FROM pj_import_raw_signed
 WHERE batch_id IN (SELECT id FROM pj_import_batch
                    WHERE source_type IN ('KE_SIGNED','KE_RECEIVED','HISTORY_PAYROLL'));
DELETE FROM pj_import_raw_received
 WHERE batch_id IN (SELECT id FROM pj_import_batch
                    WHERE source_type IN ('KE_SIGNED','KE_RECEIVED','HISTORY_PAYROLL'));
DELETE FROM pj_import_raw_payroll
 WHERE batch_id IN (SELECT id FROM pj_import_batch
                    WHERE source_type IN ('KE_SIGNED','KE_RECEIVED','HISTORY_PAYROLL'));
DELETE FROM pj_import_issue
 WHERE batch_id IN (SELECT id FROM pj_import_batch
                    WHERE source_type IN ('KE_SIGNED','KE_RECEIVED','HISTORY_PAYROLL'));
DELETE FROM pj_normalized_record
 WHERE batch_id IN (SELECT id FROM pj_import_batch
                    WHERE source_type IN ('KE_SIGNED','KE_RECEIVED','HISTORY_PAYROLL'));
DELETE FROM pj_import_batch
 WHERE source_type IN ('KE_SIGNED','KE_RECEIVED','HISTORY_PAYROLL');

-- ---------------- 五、相关 Outbox 事件 ----------------
DELETE FROM pj_outbox_idempotent
 WHERE event_id IN (SELECT event_id FROM pj_event_outbox
                    WHERE event_type IN ('import.batch.archived','import.batch.revoked','import.batch.renormalized',
                                         'performance.fact.created','performance.fact.reversed',
                                         'received.apply.approved','commission.approved','period.reopened'));
DELETE FROM pj_event_outbox
 WHERE event_type IN ('import.batch.archived','import.batch.revoked','import.batch.renormalized',
                      'performance.fact.created','performance.fact.reversed',
                      'received.apply.approved','commission.approved','period.reopened');

-- ---------------- 六、相关审批流程实例 ----------------
DELETE FROM flow_user
 WHERE associated IN (SELECT id FROM flow_task
                      WHERE instance_id IN (SELECT id FROM flow_instance
                                            WHERE definition_id IN (SELECT id FROM flow_definition
                                                                    WHERE flow_code IN ('perf_adjust','perf_received','perf_received_adjust','perf_refund','commission_apply'))));
DELETE FROM flow_his_task
 WHERE instance_id IN (SELECT id FROM flow_instance
                       WHERE definition_id IN (SELECT id FROM flow_definition
                                               WHERE flow_code IN ('perf_adjust','perf_received','perf_received_adjust','perf_refund','commission_apply')));
DELETE FROM flow_task
 WHERE instance_id IN (SELECT id FROM flow_instance
                       WHERE definition_id IN (SELECT id FROM flow_definition
                                               WHERE flow_code IN ('perf_adjust','perf_received','perf_received_adjust','perf_refund','commission_apply')));
DELETE FROM flow_instance_biz_ext
 WHERE instance_id IN (SELECT id FROM flow_instance
                       WHERE definition_id IN (SELECT id FROM flow_definition
                                               WHERE flow_code IN ('perf_adjust','perf_received','perf_received_adjust','perf_refund','commission_apply')));
DELETE FROM flow_instance
 WHERE definition_id IN (SELECT id FROM flow_definition
                         WHERE flow_code IN ('perf_adjust','perf_received','perf_received_adjust','perf_refund','commission_apply'));

COMMIT;
SQL

echo "[完成] 清理结束，剩余数据统计:"
echo "=========================================="
print_counts
echo "=========================================="
