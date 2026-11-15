#!/bin/bash
# ============================================================================
# 盘家智管 — 脚本一：全新部署（标准交付，首次上线/换客户服务器用）
#
# 就三步：整包拷到服务器 → docker load → docker compose up
# 自带产物构建，不动数据；日常发版请用 update-backend.sh / update-frontend.sh
#
# 用法（Mac 本地执行）：
#   bash deploy.sh <服务器IP> [SSH用户]          # 首次：打包上传 + 部署
#   bash deploy.sh <服务器IP> --skip-upload      # 服务器已有文件（重跑/重启）
#   bash deploy.sh 162.14.72.197                 # 例：盘家生产（纯 HTTP，无域名时）
#
# 域名 HTTPS 模式（certbot 自动申请/续签 Let's Encrypt 证书，推荐）：
#   DOMAIN=panjia.online bash deploy.sh <服务器IP>
#   要求：域名 A 记录（含 www，如有）已指向服务器、已 ICP 备案、安全组放行 80/443
#   脚本自动：校验 DNS → 装 certbot → 申请证书（含 www 子域，若已解析）
#             → 生成 HTTPS nginx 配置 → 配每日自动续签 cron → OSS 图片域名改 https
#   也可写入 server.env：DOMAIN=panjia.online（CERT_EMAIL 可选，默认 admin@主域）
#
# 也可用环境变量（优先级：命令行参数 > 环境变量 > server.env > 默认值）：
#   SERVER_IP=1.2.3.4 SERVER_USER=root INSTALL_DIR=/opt/panjia bash deploy.sh
# ============================================================================

set -euo pipefail

# ---- 参数解析：[--skip-upload] <服务器IP> [SSH用户] ----
SKIP_UPLOAD=0
ARGS=()
for arg in "$@"; do
    case "$arg" in
        --skip-upload) SKIP_UPLOAD=1 ;;
        *) ARGS+=("$arg") ;;
    esac
done
set -- "${ARGS[@]:-}"

# 优先级：命令行参数 > 环境变量 > server.env > 默认值
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
# 先记下环境变量里已有的值（server.env 不得覆盖它们）
_ENV_SERVER_IP="${SERVER_IP:-}"
_ENV_DOMAIN="${DOMAIN:-}"
if [ -f "$SCRIPT_DIR/server.env" ]; then
    . "$SCRIPT_DIR/server.env"   # 格式：SERVER_IP=x.x.x.x SERVER_USER=ubuntu INSTALL_DIR=/opt/panjia DOMAIN=panjia.online
fi
[ -n "$_ENV_SERVER_IP" ] && SERVER_IP="$_ENV_SERVER_IP"
[ -n "$_ENV_DOMAIN" ] && DOMAIN="$_ENV_DOMAIN"
# 命令行参数优先级最高
[ -n "${1:-}" ] && SERVER_IP="$1"
[ -n "${2:-}" ] && SERVER_USER="$2"
SERVER_USER="${SERVER_USER:-ubuntu}"
DOMAIN="${DOMAIN:-}"
CERT_EMAIL="${CERT_EMAIL:-}"
SERVER_IP="${SERVER_IP:?请传入服务器 IP：bash deploy.sh <IP> [用户]，或在 server.env 里配置}"
REMOTE_DIR="${INSTALL_DIR:-/home/$SERVER_USER/panjia}"
if [ "$SERVER_USER" = "root" ]; then REMOTE_DIR="${INSTALL_DIR:-/root/panjia}"; fi
SSH_TARGET="${SERVER_USER}@${SERVER_IP}"

IMAGE_TAR="$SCRIPT_DIR/panjia-server-latest.tar"

RED='\033[0;31m'; GREEN='\033[0;32m'; BLUE='\033[0;34m'; NC='\033[0m'
info()  { printf '%b[INFO]%b  %s\n' "$BLUE" "$NC" "$*"; }
ok()    { printf '%b[OK]%b    %s\n' "$GREEN" "$NC" "$*"; }
warn()  { printf '%b[WARN]%b  %s\n' '\033[0;33m' "$NC" "$*"; }
fail()  { printf '%b[FAIL]%b  %s\n' "$RED" "$NC" "$*"; exit 1; }

echo ""
echo "=========================================="
echo " 盘家智管 — 整包部署到 $SERVER_IP"
if [ -n "$DOMAIN" ]; then
    echo " 模式：HTTPS 域名（certbot 自动证书）  域名：$DOMAIN"
else
    echo " 模式：纯 HTTP（未配置 DOMAIN，域名下来后加 DOMAIN=xxx 重跑本脚本即可切换）"
fi
echo "=========================================="
echo ""

# ==================== 本地检查 ====================
[ -f "$IMAGE_TAR" ] || fail "镜像 tar 不存在：$IMAGE_TAR"
[ -d "$SCRIPT_DIR/web/dist" ] || fail "前端 dist 不存在：$SCRIPT_DIR/web/dist"

# HTTPS 模式：域名 sed 特殊字符 + DNS A 记录预校验，并探测 www 是否一起签
CERT_WWW_DOMAIN=""
if [ -n "$DOMAIN" ]; then
    if [[ "$DOMAIN" =~ [\&\|\/] ]]; then
        fail "域名包含 sed 特殊字符（&/|），无法生成 nginx 配置：$DOMAIN"
    fi
    if command -v dig >/dev/null 2>&1; then
        RESOLVED_IP="$(dig +short "$DOMAIN" 2>/dev/null | grep -E '^[0-9.]+$' | tail -1)"
        [ -n "$RESOLVED_IP" ] || fail "域名 ${DOMAIN} 解析失败，请先在域名服务商配置 A 记录指向 ${SERVER_IP}"
        [ "$RESOLVED_IP" = "$SERVER_IP" ] || fail "域名 ${DOMAIN} 解析到 ${RESOLVED_IP}，与服务器 ${SERVER_IP} 不一致，等 DNS 生效后重试"
        # www 解析到同一台服务器才纳入同一张证书（否则只签主域，避免 ACME 验证整体失败）
        WWW_IP="$(dig +short "www.${DOMAIN}" 2>/dev/null | grep -E '^[0-9.]+$' | tail -1)"
        if [ -n "$WWW_IP" ] && [ "$WWW_IP" = "$SERVER_IP" ]; then
            CERT_WWW_DOMAIN="www.${DOMAIN}"
        else
            warn "www.${DOMAIN} 未解析到 ${SERVER_IP}，证书只签 ${DOMAIN}（www 暂不可用，解析生效后重跑可补）"
        fi
        ok " DNS 校验通过：${DOMAIN} → ${RESOLVED_IP}${CERT_WWW_DOMAIN:+（含 ${CERT_WWW_DOMAIN}）}"
    else
        warn "本机无 dig，跳过 DNS 预校验（请确认 A 记录已指向 ${SERVER_IP}，否则 certbot 申请会失败）"
    fi
    [ -z "$CERT_EMAIL" ] && CERT_EMAIL="admin@${DOMAIN#www.}"
fi

ssh -o ConnectTimeout=8 -o BatchMode=yes "$SSH_TARGET" "echo ok" >/dev/null 2>&1 \
    || fail "SSH 连不上 ${SSH_TARGET}（先 ssh-copy-id）"
ok " 本地检查通过（tar $(du -sh "$IMAGE_TAR" | cut -f1)）"

# ==================== 1. 打包上传 ====================
if [ "$SKIP_UPLOAD" = "0" ]; then
    info ">>> 步骤 1/3：打包上传（排除 data/.env/logs，不动服务器数据）"
    TARBALL="/tmp/panjia-deploy.tgz"
    tar czf "$TARBALL" -C "$SCRIPT_DIR" \
        --exclude './data' --exclude './.env' --exclude './logs' \
        --exclude './data/*' --exclude './logs/*' \
        --exclude './panjia.online_nginx' \
        .
    info " 上传 $(du -sh "$TARBALL" | cut -f1)..."
    ssh "$SSH_TARGET" "mkdir -p $REMOTE_DIR"
    scp -q "$TARBALL" "$SSH_TARGET:/tmp/"
    ssh "$SSH_TARGET" "tar xzf /tmp/panjia-deploy.tgz -C $REMOTE_DIR && rm /tmp/panjia-deploy.tgz"
    rm -f "$TARBALL"
    ok " 上传解压完成"
else
    info ">>> 步骤 1/3：跳过上传（--skip-upload）"
fi

# ==================== 2. 服务器：修加速源 + load 镜像 ====================
info ">>> 步骤 2/3：服务器配置（加速源 + 导入镜像${DOMAIN:+ + HTTPS 证书}）"
# 变量通过 env 显式传给远程 sudo bash（heredoc 是 quoted 的，不会自动透传）：
#   PANJIA_AUTH_CODE 首次激活；SERVER_IP 拼 OSS 图片直链；
#   DOMAIN/CERT_* 走 certbot HTTPS；REMOTE_DIR 供续签 cron 写 webroot 绝对路径
ssh -o ServerAliveInterval=30 "$SSH_TARGET" "sudo env \
  PANJIA_AUTH_CODE='${PANJIA_AUTH_CODE:-}' \
  SERVER_IP='${SERVER_IP}' \
  DOMAIN='${DOMAIN}' \
  CERT_WWW_DOMAIN='${CERT_WWW_DOMAIN:-}' \
  CERT_EMAIL='${CERT_EMAIL:-}' \
  REMOTE_DIR='${REMOTE_DIR}' \
  bash" << 'REMOTE'
set -e
cd "$REMOTE_DIR"

# ---- Docker 加速源（幂等：没配才写，写了就 restart 生效）----
if ! grep -q registry-mirrors /etc/docker/daemon.json 2>/dev/null; then
    echo "[服务器] 配置镜像加速源..."
    mkdir -p /etc/docker
    cat > /etc/docker/daemon.json << 'DCEOF'
{
    "registry-mirrors": [
        "https://mirror.ccs.tencentyun.com",
        "https://docker.m.daocloud.io",
        "https://docker.1ms.run",
        "https://docker.1panel.live"
    ],
    "log-driver": "json-file",
    "log-opts": { "max-size": "100m", "max-file": "5" },
    "live-restore": true
}
DCEOF
    systemctl restart docker
    sleep 2
    echo "[服务器] 加速源已配置（第一个走腾讯云内网，CVM 必通）"
else
    echo "[服务器] 加速源已配置，跳过"
fi

# ---- 导入后端镜像（每次都 load 覆盖同 tag 旧镜像；不能"已存在跳过"，否则镜像更新不生效）----
echo "[服务器] docker load 后端镜像..."
docker load -i panjia-server-latest.tar

# ---- 预拉基础镜像（postgres/redis/minio/nginx，走加速源）----
for img in postgres:16.9 redis:7-alpine nginx:stable-alpine pgsty/minio:RELEASE.2026-04-17T00-00-00Z; do
    if docker image inspect "$img" >/dev/null 2>&1; then
        echo "[服务器] $img 已存在"
    else
        echo "[服务器] 拉取 $img ..."
        docker pull "$img"
    fi
done

# ---- .env（幂等：已有则保留，绝不覆盖密码）----
if [ ! -f .env ]; then
    echo "[服务器] 首次生成 .env（随机强密码）..."
    DB_PW="Pg$(openssl rand -hex 10)!"
    RD_PW="Rd$(openssl rand -hex 10)!"
    MN_PW="Mn$(openssl rand -hex 10)!"
    cat > .env << ENVEOF
POSTGRES_USER=panjia
POSTGRES_PASSWORD=$DB_PW
POSTGRES_DB=panjia
POSTGRES_PORT=5432
REDIS_PASSWORD=$RD_PW
REDIS_PORT=6379
MINIO_ROOT_USER=panjia
MINIO_ROOT_PASSWORD=$MN_PW
MINIO_API_PORT=9000
MINIO_CONSOLE_PORT=9001
IMAGE_TAG=latest
WEB_PORT=80
# 内置 MinIO 随 compose 启动；改用外部独立 MinIO 时把本行置空并填好下面 4 项
COMPOSE_PROFILES=embedded-minio
# PANJIA_IMPORT_MINIO_ENDPOINT=http://文件服务器IP:9000
# PANJIA_IMPORT_MINIO_ACCESS_KEY=外部MinIO账号
# PANJIA_IMPORT_MINIO_SECRET_KEY=外部MinIO密码
# PANJIA_IMPORT_MINIO_BUCKET=panjia-archive
JAVA_OPTS="-Xms2048m -Xmx4096m -XX:+UseG1GC"
PANJIA_AUTH_CODE=${PANJIA_AUTH_CODE:-}
# License 授权服务器（主站 console）与 SSL Pinning 公钥指纹
PANJIA_LICENSE_SERVER_URL=https://panjia.online
PANJIA_PIN_FP_A=5f9a4d5dda12751bfa7a7d5fd490a83d5ae0e6aae5abc6eb06a64e21a41e94ce
PANJIA_PIN_FP_B=
# ---- 钉钉集成（考勤/积分同步）：开关、凭证、同步时间等统一在「参数管理」维护，不走环境变量 ----
ENVEOF
    # HTTPS 域名模式：compose 据此挂载 HTTPS 配置 + 对公网发布 443
    if [ -n "$DOMAIN" ]; then
        cat >> .env << ENVEOF

# ---- HTTPS 域名（certbot 证书，deploy.sh 自动维护）----
DOMAIN=$DOMAIN
NGINX_CONF=nginx-https.enabled.conf
WEB_HTTPS_BIND=443
ENVEOF
    fi
    chmod 600 .env
    echo "[服务器] .env 已生成"
    if [ -n "${PANJIA_AUTH_CODE:-}" ]; then echo "[服务器] 已写入授权码（首次启动自动激活）"; fi
else
    echo "[服务器] .env 已存在，保留现有密码"
    # 老版本 .env 升级：补齐 MinIO / License 授权码配置（幂等）
    if ! grep -q MINIO_ROOT_PASSWORD .env; then
        MN_PW="Mn$(openssl rand -hex 10)!"
        cat >> .env << MNEOF

# ---- MinIO 对象存储（自动补充）----
MINIO_ROOT_USER=panjia
MINIO_ROOT_PASSWORD=$MN_PW
MINIO_API_PORT=9000
MINIO_CONSOLE_PORT=9001
MNEOF
        echo "[服务器] .env 已补充 MinIO 配置（MinIO 密码：${MN_PW}）"
    fi
    if ! grep -q '^PANJIA_AUTH_CODE=' .env; then
        echo "PANJIA_AUTH_CODE=${PANJIA_AUTH_CODE:-}" >> .env
        echo "[服务器] .env 已补充 PANJIA_AUTH_CODE"
    fi
    # 老版本升级：License 授权地址迁到主站 console，并补齐 SSL Pinning 公钥指纹
    if ! grep -q '^PANJIA_LICENSE_SERVER_URL=' .env; then
        cat >> .env << LICEOF

# ---- License 授权服务器与 SSL Pinning（自动补充）----
PANJIA_LICENSE_SERVER_URL=https://panjia.online
PANJIA_PIN_FP_A=5f9a4d5dda12751bfa7a7d5fd490a83d5ae0e6aae5abc6eb06a64e21a41e94ce
PANJIA_PIN_FP_B=
LICEOF
        echo "[服务器] .env 已补充 License 授权地址与公钥指纹"
    elif ! grep -q '^PANJIA_PIN_FP_A=' .env; then
        cat >> .env << LICEOF
PANJIA_PIN_FP_A=5f9a4d5dda12751bfa7a7d5fd490a83d5ae0e6aae5abc6eb06a64e21a41e94ce
PANJIA_PIN_FP_B=
LICEOF
        echo "[服务器] .env 已补充 SSL Pinning 公钥指纹"
    fi
    # 老版本升级：默认启用内置 MinIO（外置 MinIO 用户自行置空）
    if ! grep -q '^COMPOSE_PROFILES=' .env; then
        echo "COMPOSE_PROFILES=embedded-minio" >> .env
        echo "[服务器] .env 已补充 COMPOSE_PROFILES=embedded-minio"
    fi
    # 老版本升级：HTTP → HTTPS 域名模式（本次带 DOMAIN 部署时补齐，幂等）
    if [ -n "$DOMAIN" ]; then
        grep -q '^DOMAIN=' .env || echo "DOMAIN=$DOMAIN" >> .env
        if ! grep -q '^NGINX_CONF=' .env; then
            echo "NGINX_CONF=nginx-https.enabled.conf" >> .env
            echo "[服务器] .env 已补充 NGINX_CONF（启用 HTTPS 配置）"
        fi
        if ! grep -q '^WEB_HTTPS_BIND=' .env; then
            echo "WEB_HTTPS_BIND=443" >> .env
            echo "[服务器] .env 已补充 WEB_HTTPS_BIND=443（对公网发布 HTTPS）"
        fi
    fi
fi

# ---- 清理上次失败残留的容器，再启动 ----
docker compose down --remove-orphans 2>/dev/null || true
chmod +x *.sh 2>/dev/null || true

# ==================== HTTPS 域名模式：certbot 证书（申请/续签配置） ====================
# 放在 down 之后、up 之前：standalone 申请需要 80 端口空闲（compose down 已释放）
if [ -n "$DOMAIN" ]; then
    # ---- 安装 certbot（处理新装 Ubuntu unattended-upgrades 占 apt/dpkg 锁）----
    if ! command -v certbot >/dev/null 2>&1; then
        echo "[服务器] 安装 certbot..."
        export DEBIAN_FRONTEND=noninteractive
        systemctl stop unattended-upgrades 2>/dev/null || true
        systemctl disable unattended-upgrades 2>/dev/null || true
        for pat in unattended-upgr apt-get dpkg; do
            PIDS=$(pgrep -f "$pat" 2>/dev/null || true)
            [ -n "$PIDS" ] && kill -9 $PIDS 2>/dev/null || true
        done
        sleep 2
        rm -f /var/lib/dpkg/lock-frontend /var/lib/dpkg/lock /var/cache/apt/archives/lock 2>/dev/null || true
        dpkg --configure -a 2>/dev/null || true
        apt-get update -qq
        apt-get install -y -qq certbot
        echo "[服务器] certbot 安装完成"
    else
        echo "[服务器] certbot 已安装：$(certbot --version 2>/dev/null || echo unknown)"
    fi

    # ---- 申请证书（已存在跳过；续签由 cron 自动做）----
    CERT_PATH="/etc/letsencrypt/live/$DOMAIN/fullchain.pem"
    if [ -f "$CERT_PATH" ]; then
        echo "[服务器] 证书已存在，跳过申请（/etc/letsencrypt/live/$DOMAIN/）"
    else
        CERT_ARGS="-d $DOMAIN"
        [ -n "$CERT_WWW_DOMAIN" ] && CERT_ARGS="$CERT_ARGS -d $CERT_WWW_DOMAIN"
        echo "[服务器] 申请 Let's Encrypt 证书：$CERT_ARGS（邮箱 $CERT_EMAIL）"
        certbot certonly --standalone $CERT_ARGS \
            --non-interactive --agree-tos --email "$CERT_EMAIL" --keep-until-expiring
        if [ ! -f "$CERT_PATH" ]; then
            echo "[ERROR] 证书申请失败：检查 1) 域名 A 记录指向本机  2) 已 ICP 备案  3) 安全组放行 80"
            exit 1
        fi
        echo "[服务器] 证书申请成功"
    fi

    # ---- 生成 HTTPS nginx 成品配置（模板 __DOMAIN__ 替换为真实域名）----
    mkdir -p acme
    sed "s|__DOMAIN__|$DOMAIN|g" nginx/nginx-https.conf > nginx/nginx-https.enabled.conf
    chmod 644 nginx/nginx-https.enabled.conf
    echo "[服务器] 已生成 nginx/nginx-https.enabled.conf（域名 $DOMAIN）"

    # ---- 自动续签 cron：每天 3:00 检查，webroot 验证（不用停 nginx），成功后 reload ----
    cat > /etc/cron.d/panjia-certbot << CRONEOF
# 盘家智管 Let's Encrypt 证书自动续签（certbot renew 只在临近到期才真续）
0 3 * * * root certbot renew --webroot -w $REMOTE_DIR/acme --quiet && docker exec panjia-web nginx -s reload 2>/dev/null || true
CRONEOF
    chmod 644 /etc/cron.d/panjia-certbot
    echo "[服务器] 证书续签 cron 已设置：/etc/cron.d/panjia-certbot（每天 3:00）"
fi

# 启动策略（关键）：
# 1) up -d 全部创建启动，不阻塞 —— web 只 depends_on server 启动，后端挂了 nginx 也照常跑
# 2) 基础设施（pg/redis/minio）必须健康，才继续；不健康直接判失败
# 3) server 只做宽限等待（最多 ~150s），不健康不阻断 —— 常见原因是还没配授权码，
#    部署完成后补 .env 再 ./restart.sh 即可，不应拖死整个部署
echo "[服务器] docker compose up -d（全部容器创建启动）..."
docker compose up -d

# HTTPS 模式下 nginx 若因证书路径/配置错误起不来，第一时间打印日志，避免到健康检查才发现
if [ -n "$DOMAIN" ]; then
    sleep 3
    if [ "$(docker inspect -f '{{.State.Running}}' panjia-web 2>/dev/null || echo missing)" != "true" ]; then
        echo "[WARN] nginx(panjia-web) 未正常运行，最近日志（常见原因：证书文件缺失/配置语法错误）："
        docker logs --tail 30 panjia-web 2>&1 || true
    fi
fi

# 判断内置/外部 MinIO（.env 里 COMPOSE_PROFILES 含 embedded-minio 为内置）
# 逐行解析而非 source：兼容老版本生成的无引号含空格值（如 JAVA_OPTS=-Xms.. -Xmx..，source 会把第二个词当命令）
while IFS= read -r _line || [ -n "$_line" ]; do
    case "$_line" in ''|'#'*) continue;; esac
    _k="${_line%%=*}"
    case "$_k" in *[!A-Za-z0-9_]*) continue;; esac
    _v="${_line#*=}"
    if [ "${_v:0:1}" = '"' ] && [ "${_v: -1}" = '"' ]; then _v="${_v:1:${#_v}-2}"; fi
    if [ "${_v:0:1}" = "'" ] && [ "${_v: -1}" = "'" ]; then _v="${_v:1:${#_v}-2}"; fi
    printf -v "$_k" '%s' "$_v"; export "$_k"
done < ./.env
MC_IMAGE="pgsty/minio:RELEASE.2026-04-17T00-00-00Z"
IMPORT_BUCKET="${PANJIA_IMPORT_MINIO_BUCKET:-panjia-archive}"

if docker compose config --services | grep -q '^minio$'; then
    echo "[服务器] 等待基础设施健康（postgres/redis/内置minio，最多 5 分钟）..."
    docker compose up -d --wait --wait-timeout 300 postgres redis minio
    # OSS 客户端不会自动建桶；mc 已内置在 minio 镜像里，幂等
    echo "[服务器] 确保内置 MinIO 归档桶 ${IMPORT_BUCKET} ..."
    docker exec panjia-minio sh -c '
        mc alias set local http://127.0.0.1:9000 "$MINIO_ROOT_USER" "$MINIO_ROOT_PASSWORD" >/dev/null 2>&1
        if mc ls "local/'"$IMPORT_BUCKET"'" >/dev/null 2>&1; then
            echo "[服务器] 桶 '"$IMPORT_BUCKET"' 已存在"
        else
            mc mb "local/'"$IMPORT_BUCKET"'" && echo "[服务器] 已创建桶 '"$IMPORT_BUCKET"'"
        fi
        # 业务桶 panjia-file（sys_oss_config 默认 bucket，考勤截图等图片上传/展示）：
        # 建桶 + 公开下载（access_policy=1 public，浏览器直链预览依赖匿名可读；归档桶不开放）
        if mc ls local/panjia-file >/dev/null 2>&1; then
            echo "[服务器] 桶 panjia-file 已存在"
        else
            mc mb local/panjia-file && echo "[服务器] 已创建桶 panjia-file"
        fi
        mc anonymous set download local/panjia-file >/dev/null && echo "[服务器] panjia-file 桶已开放公开下载"'
    # sys_oss_config 修正参数（UPDATE 统一在 server 健康后执行，见下文）：
    #   endpoint=minio:9000 —— server 容器走 compose 内网服务名（baseline 的 host.docker.internal 在 Linux 容器不可解析）
    #   domain_url —— 浏览器图片直链，经 nginx /oss/ 反代到 minio:9000（minio 只绑 127.0.0.1）
    #                 HTTPS 域名模式必须用 https://域名/oss，否则页面被浏览器按混合内容拦截图片
    #                 纯 HTTP 模式必须带 .env 的 WEB_PORT（测试环境 8081 与生产 80 同机，
    #                 漏端口会把回显打到生产 80 上，图片 404/裂图）
    #   凭证 = .env 的 MINIO_ROOT_USER/PASSWORD（baseline 的 ruoyi/ruoyi123 与服务器随机密码不符）
    OSS_CFG_ENDPOINT='minio:9000'
    if [ -n "$DOMAIN" ]; then
        OSS_CFG_DOMAIN="https://${DOMAIN}/oss"
    else
        OSS_WEB_PORT="${WEB_PORT:-80}"
        if [ "${OSS_WEB_PORT}" = "80" ]; then
            OSS_CFG_DOMAIN="http://${SERVER_IP}/oss"
        else
            OSS_CFG_DOMAIN="http://${SERVER_IP}:${OSS_WEB_PORT}/oss"
        fi
    fi
    OSS_CFG_AK="${MINIO_ROOT_USER}"
    OSS_CFG_SK="${MINIO_ROOT_PASSWORD}"
else
    EXT_EP="${PANJIA_IMPORT_MINIO_ENDPOINT:?外置 MinIO 模式必须在 .env 配置 PANJIA_IMPORT_MINIO_ENDPOINT}"
    echo "[服务器] 外置 MinIO 模式：${EXT_EP}（内置 minio 容器不启动）"
    echo "[服务器] 等待基础设施健康（postgres/redis，最多 5 分钟）..."
    docker compose up -d --wait --wait-timeout 300 postgres redis
    echo "[服务器] 连接外置 MinIO 并确保归档桶 ${IMPORT_BUCKET} ..."
    docker run --rm --entrypoint sh "$MC_IMAGE" -c '
        mc alias set ext "'"$EXT_EP"'" "'"$PANJIA_IMPORT_MINIO_ACCESS_KEY"'" "'"$PANJIA_IMPORT_MINIO_SECRET_KEY"'" >/dev/null 2>&1 \
            || { echo "[服务器] 连不上外置 MinIO，请检查地址/密钥/安全组"; exit 1; }
        if mc ls "ext/'"$IMPORT_BUCKET"'" >/dev/null 2>&1; then
            echo "[服务器] 桶 '"$IMPORT_BUCKET"' 已存在"
        else
            mc mb "ext/'"$IMPORT_BUCKET"'" && echo "[服务器] 已创建桶 '"$IMPORT_BUCKET"'"
        fi
        # 业务桶 panjia-file（图片上传/展示）：建桶 + 公开下载
        if mc ls ext/panjia-file >/dev/null 2>&1; then
            echo "[服务器] 桶 panjia-file 已存在"
        else
            mc mb ext/panjia-file && echo "[服务器] 已创建桶 panjia-file"
        fi
        mc anonymous set download ext/panjia-file >/dev/null && echo "[服务器] panjia-file 桶已开放公开下载"'
    # sys_oss_config 修正参数：endpoint/domain_url 均为外置地址（server 容器与浏览器都直连外置机；
    # 浏览器需能访问该地址 9000 端口），凭证 = .env 的外置账号
    EXT_HOST="${EXT_EP#http://}"; EXT_HOST="${EXT_HOST#https://}"
    OSS_CFG_ENDPOINT="${EXT_HOST}"
    OSS_CFG_DOMAIN="${EXT_EP}"
    OSS_CFG_AK="${PANJIA_IMPORT_MINIO_ACCESS_KEY}"
    OSS_CFG_SK="${PANJIA_IMPORT_MINIO_SECRET_KEY}"
fi

echo "[服务器] 等待后端 server 健康（宽限 ~150 秒，不阻断部署）..."
SRV_HEALTH="starting"
for i in $(seq 1 30); do
    SRV_HEALTH=$(docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' panjia-server 2>/dev/null || echo missing)
    [ "$SRV_HEALTH" = "healthy" ] && break
    sleep 5
done

# sys_oss_config 修正 + 重启生效（必须在 server 健康后执行：
# 全新部署时 Flyway 迁移约 1-2 分钟建完表，提前 UPDATE 会因表不存在被 set -e 中断部署；
# server 未健康时不拖死部署，降级为告警，恢复后重跑一次 --skip-upload 自动补齐）
if [ "$SRV_HEALTH" = "healthy" ]; then
    echo "[服务器] 修正 sys_oss_config（endpoint/domain_url/凭证 对齐 MinIO）..."
    docker exec -e PGPASSWORD="$POSTGRES_PASSWORD" panjia-postgres psql -U "$POSTGRES_USER" -d "${POSTGRES_DB:-panjia}" -c \
        "UPDATE sys_oss_config SET endpoint='${OSS_CFG_ENDPOINT}', domain_url='${OSS_CFG_DOMAIN}', bucket_name='panjia-file', access_key='${OSS_CFG_AK}', secret_key='${OSS_CFG_SK}', update_time=now() WHERE config_key='minio';"
    # 重启使变更生效（幂等，新部署容器本就重建，仅多几秒）：
    #   · web    —— nginx.conf 新增 /oss/ 反代（bind-mount :ro，需 restart 重读）
    #   · server —— sys_oss_config 为直改库，OSS 客户端缓存需重启刷新
    echo "[服务器] 重启 web / server（OSS 反代与配置生效）..."
    docker compose restart web server
else
    echo "[WARN] server 未健康：sys_oss_config 修正与 /oss/ 反代重启未执行"
    echo "         待 server 恢复后重跑 bash deploy.sh <IP> --skip-upload 自动补齐（幂等）"
fi

echo "[服务器] 当前容器状态："
docker compose ps
if [ "$SRV_HEALTH" != "healthy" ]; then
    echo ""
    echo "[WARN] 后端 server 当前状态：${SRV_HEALTH}（前端 nginx 不受影响，已正常启动）"
    echo "[WARN] 常见原因：尚未配置授权码。处理："
    echo "         1) sudo nano $PWD/.env  填入 PANJIA_AUTH_CODE=PJ-XXXX-XXXX-XXXX"
    echo "         2) sudo ./restart.sh"
    echo "         查日志：sudo ./logs.sh"
    echo ""
fi
REMOTE
ok " 服务器启动完成"

# ==================== 3. 健康检查 ====================
info ">>> 步骤 3/3：健康检查"
sleep 3
if [ -n "$DOMAIN" ]; then
    CODE=$(curl -s -m 10 -o /dev/null -w "%{http_code}" "https://$DOMAIN" 2>/dev/null || echo "000")
    if [ "$CODE" = "200" ]; then ok " HTTPS 前端正常（https://$DOMAIN → 200）"; else warn " HTTPS 返回 ${CODE}（查日志：./logs.sh web）"; fi
    REDIRECT=$(curl -s -m 10 -o /dev/null -w "%{http_code}" "http://$DOMAIN" 2>/dev/null || echo "000")
    if [ "$REDIRECT" = "301" ]; then ok " HTTP → HTTPS 跳转正常（301）"; else warn " HTTP 跳转返回 ${REDIRECT}（应为 301）"; fi
else
    CODE=$(curl -s -m 10 -o /dev/null -w "%{http_code}" "http://$SERVER_IP" 2>/dev/null || echo "000")
    if [ "$CODE" = "200" ] || [ "$CODE" = "301" ]; then ok " 前端正常（HTTP ${CODE}）"; else warn " 前端返回 ${CODE}"; fi
fi

echo ""
echo "=========================================="
if [ -n "$DOMAIN" ]; then
    ok " 部署完成！访问 https://$DOMAIN"
    echo "  证书：  /etc/letsencrypt/live/$DOMAIN/（cron 每天 3:00 自动续签，无需人工）"
    echo "  存量图片仍是 http://IP 旧链接时，服务器上执行：cd $REMOTE_DIR && sudo bash fix-oss.sh"
else
    ok " 部署完成！访问 http://$SERVER_IP"
fi
echo "  日志：  ssh $SSH_TARGET 'cd $REMOTE_DIR && ./logs.sh'"
echo "  重启：  ssh $SSH_TARGET 'cd $REMOTE_DIR && ./restart.sh'"
echo "  密码：  ssh $SSH_TARGET 'sudo cat $REMOTE_DIR/.env'"
echo "=========================================="
