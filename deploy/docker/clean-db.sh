#!/bin/bash
# ============================================================================
# 盘家智管 — 清库脚本（上线前清洗测试/预发布脏数据）
#
# 清什么：业务运行数据 + 工作流实例 + 调度/重试运行记录 + Redis + MinIO 业务文件
# 保留什么：Flyway 版本表、系统菜单/字典/角色/OSS 配置、工作流定义、任务配置、AI 配置、
#           算薪规则、期间配置、sys_dept/sys_user（上线前录入的正式组织和账号）
#
# 用法（Mac 本地执行，服务器信息读本目录 server.env）：
#   bash clean-db.sh prod                       # 清生产库（危险！）
#   bash clean-db.sh test                       # 清测试库
#   bash clean-db.sh prod <服务器IP> [SSH用户]   # 临时指定其它服务器
#   bash clean-db.sh --dry-run test             # 只预演，不实际执行
# ============================================================================
set -euo pipefail

# ---- 参数解析 ----
DRY_RUN=false
DEPLOY_ENV=""
ARGS=()
for a in "$@"; do
    case "$a" in
        --dry-run|-n) DRY_RUN=true ;;
        test|prod)    DEPLOY_ENV="$a" ;;
        *)            ARGS+=("$a") ;;
    esac
done
[ -z "$DEPLOY_ENV" ] && { echo "用法: bash clean-db.sh [--dry-run] test|prod [IP] [SSH用户]"; exit 1; }
ARG_IP="${ARGS[0]:-}"
ARG_USER="${ARGS[1]:-}"

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
if [ -f "$SCRIPT_DIR/server.env" ]; then . "$SCRIPT_DIR/server.env"; fi
SERVER_IP="${ARG_IP:-${SERVER_IP:?请传入服务器 IP 或先配置 server.env}}"
SERVER_USER="${ARG_USER:-${SERVER_USER:-ubuntu}}"
REMOTE_DIR="${INSTALL_DIR:-/home/$SERVER_USER/panjia}"
SSH_TARGET="${SERVER_USER}@${SERVER_IP}"

# ---- 目标环境映射 ----
TEST_DEPLOY_DIR="$(dirname "$REMOTE_DIR")/panjia-test"
if [ "$DEPLOY_ENV" = "test" ]; then
    TARGET_DIR="$TEST_DEPLOY_DIR"
    ENV_NAME="测试"
    DB_NAME="panjia_test"
    CONTAINER_PREFIX="panjia-test"
else
    TARGET_DIR="$REMOTE_DIR"
    ENV_NAME="生产"
    DB_NAME="panjia"
    CONTAINER_PREFIX="panjia"
fi

RED='\033[0;31m'; GREEN='\033[0;32m'; BLUE='\033[0;34m'; YELLOW='\033[1;33m'; NC='\033[0m'
info()  { printf '%b[INFO]%b  %s\n' "$BLUE" "$NC" "$*"; }
ok()    { printf '%b[OK]%b    %s\n' "$GREEN" "$NC" "$*"; }
warn()  { printf '%b[WARN]%b  %s\n' "$YELLOW" "$NC" "$*"; }
fail()  { printf '%b[FAIL]%b  %s\n' "$RED" "$NC" "$*"; exit 1; }

echo ""
echo "=========================================="
echo " 盘家智管 — 清库脚本【${ENV_NAME}环境】→ $SSH_TARGET"
$DRY_RUN && echo " $YELLOW ⚠  DRY-RUN 模式，不会实际执行任何操作 $NC"
echo "=========================================="

# ---- 前置校验 ----
info ">>> 连通性检查"
ssh -o ConnectTimeout=8 -o BatchMode=yes "$SSH_TARGET" "echo ok" >/dev/null 2>&1 \
    || fail "SSH 连不上 ${SSH_TARGET}"
ssh -o BatchMode=yes "$SSH_TARGET" "test -f $TARGET_DIR/docker-compose.yml" 2>/dev/null \
    || fail "服务器上未找到 ${ENV_NAME} 部署 $TARGET_DIR"
ok " 目标环境：${ENV_NAME} ${TARGET_DIR}（DB: ${DB_NAME}，容器前缀: ${CONTAINER_PREFIX}-*）"

# ---- 二次确认 ----
if ! $DRY_RUN; then
    echo ""
    printf '%b[确认]%b 即将清空【%b%s%b】业务数据，保留种子/配置表。\n' \
        "$YELLOW" "$NC" "$RED" "$ENV_NAME" "$NC"
    printf '%b[确认]%b 输入 %bYES%b 继续，其它任意键退出：' "$YELLOW" "$NC" "$RED" "$NC"
    read -r CONFIRM
    [ "$CONFIRM" != "YES" ] && { echo "已取消"; exit 0; }
fi

# ================================================================
# 远端脚本（heredoc → 变量 → echo | ssh sudo bash）
#   本地变量 $TARGET_DIR / $CONTAINER_PREFIX / $DB_NAME 在 << REMOTE 时展开
#   远端变量 \$POSTGRES_PASSWORD 等用 \ 转义（远端自己读 .env）
# ================================================================
REMOTE_SCRIPT="$(cat << REMOTE
set -e
PREFIX='${CONTAINER_PREFIX}'
DB='${DB_NAME}'
TARGET='${TARGET_DIR}'

# 读 .env（JAVA_OPTS 这类无引号多值行会让 bash 尝试执行 -Xmx... 当命令，exit 127；
#   2>/dev/null 只吞输出不吞 exit code，必须 || true 否则 set -e 会炸）
set -a
. "\$TARGET/.env" 2>/dev/null || true
set +a

echo '[1/5] 备份...'
TS=\$(date +%Y%m%d_%H%M%S)
BACKUP="/tmp/\${PREFIX}-\${TS}.sql.gz"
docker exec -e PGPASSWORD="\$POSTGRES_PASSWORD" \${PREFIX}-postgres \
    pg_dump -U "\$POSTGRES_USER" -d "\$DB" | gzip > "\$BACKUP"
ls -lh "\$BACKUP"
find /tmp -maxdepth 1 -name '\${PREFIX}-*.sql.gz' -mtime +3 -delete 2>/dev/null || true

echo '[2/5] 停 server...'
cd "\$TARGET"
docker compose stop server

echo '[3/5] TRUNCATE 业务表...'
SQL=\$(docker exec -e PGPASSWORD="\$POSTGRES_PASSWORD" \${PREFIX}-postgres \
    psql -U "\$POSTGRES_USER" -d "\$DB" -Atc "SELECT 'TRUNCATE TABLE ' || string_agg(tablename, ', ') || ' RESTART IDENTITY CASCADE;' FROM pg_tables WHERE schemaname='public' AND tablename NOT IN ('flyway_schema_history','sys_user','sys_role','sys_role_dept','sys_role_menu','sys_user_role','sys_user_post','sys_menu','sys_dict_type','sys_dict_data','sys_dept','sys_post','sys_oss_config','sys_config','sys_social','sys_client','flow_category','flow_definition','flow_node','flow_spel','flow_skip','flow_user','sj_job','sj_job_executor','sj_namespace','sj_group_config','sai_model_provider','sai_model_config','sai_app','sai_agent','sai_agent_skill','sai_agent_mcp_server','sai_mcp_server','sai_skill','sai_skill_file','sai_openapi_user','sai_user','sai_resource','sai_store_instance','sai_user_agent','pj_payroll_policy_rule','pj_payroll_rank_rule','pj_payroll_conversion_rule','pj_payroll_dept_monthly_config','pj_payroll_rate_adjust','pj_perf_period_close');")
echo "  SQL 长度: \${#SQL}"
docker exec -e PGPASSWORD="\$POSTGRES_PASSWORD" \${PREFIX}-postgres \
    psql -U "\$POSTGRES_USER" -d "\$DB" -v ON_ERROR_STOP=1 -c "\$SQL"
echo '  DB 清完'

echo '[4/5] Redis FLUSHDB + MinIO 清桶内文件...'
docker exec \${PREFIX}-redis redis-cli -a "\$REDIS_PASSWORD" FLUSHDB 2>/dev/null \
    && echo '  Redis 清完' || echo '  Redis 跳过'

docker exec \${PREFIX}-minio mc alias set local http://127.0.0.1:9000 "\$MINIO_ROOT_USER" "\$MINIO_ROOT_PASSWORD" >/dev/null 2>&1
for bucket in panjia-file panjia-archive; do
  docker exec \${PREFIX}-minio mc rm --recursive --force "local/\$bucket/" 2>/dev/null \
    && echo "  MinIO \$bucket 清空" || echo "  MinIO \$bucket 跳过"
done

echo '[5/5] 起 server...'
cd "\$TARGET"
docker compose up -d --wait --wait-timeout 300 server
echo "========== 清库完成 =========="
echo "备份: \$BACKUP"
echo "恢复: gunzip -c \$BACKUP | docker exec -i \${PREFIX}-postgres psql -U \$POSTGRES_USER -d \$DB"
REMOTE
)"

if $DRY_RUN; then
    echo ">>> 预演模式：以下为将发送到服务器的脚本（实际不会发送）"
    echo "$REMOTE_SCRIPT"
else
    info ">>> 远端执行清库"
    echo "$REMOTE_SCRIPT" | ssh "$SSH_TARGET" sudo bash
    ok " 远端清库完成"
fi

# ---- 本地健康检查 ----
if ! $DRY_RUN; then
    TARGET_PORT=$([ "$DEPLOY_ENV" = "test" ] && echo 8081 || echo 80)
    TARGET_URL="http://$SERVER_IP:$TARGET_PORT"
    sleep 5
    SUCC=0
    for i in $(seq 1 25); do
        CODE=$(curl -s -m 5 -o /dev/null -w "%{http_code}" "$TARGET_URL" 2>/dev/null || echo "000")
        if [ "$CODE" = "200" ] || [ "$CODE" = "301" ]; then
            ok " ${ENV_NAME}前端正常（HTTP ${CODE}，第 ${i}/25 次）"
            SUCC=1
            break
        fi
        sleep 2
    done
    [ "$SUCC" = "0" ] && warn "健康检查未通过（看日志：ssh $SSH_TARGET 'cd $TARGET_DIR && sudo docker logs ${CONTAINER_PREFIX}-server --tail 30'）"
fi

echo ""
echo "=========================================="
if $DRY_RUN; then
    ok " DRY-RUN 结束，未实际执行任何操作。确认无误后去掉 --dry-run 再跑。"
else
    ok " 清库完成【${ENV_NAME}环境】！"
fi
echo "=========================================="
