#!/bin/bash
# ============================================================================
# 盘家智管 — 服务器初始化（全新 Ubuntu 服务器，一次性执行）
#
# 做四件事（全部幂等，重复执行安全）：
#   1. 系统基础：apt 更新 + 常用工具 + 时区 Asia/Shanghai
#   2. 防火墙 ufw：放行 22/80/8080 后启用（先放行 SSH 再 enable，防锁死）
#   3. Docker CE + compose 插件：腾讯云内网源优先，不可达自动回退官方源
#   4. Docker 加速源 daemon.json：腾讯云内网 registry 源优先 + 日志限制
#
# 用法（Mac 本地执行，服务器信息自动读 ../docker/server.env）：
#   bash setup-server.sh                     # 推荐
#   bash setup-server.sh <服务器IP> [SSH用户] # 临时指定其它服务器
#
# 前提：
#   - 云控制台**安全组**已放行 22/80/8080（安全组是云层，ufw 是系统层，两层都要放）
#   - 本地已配免密：ssh-copy-id ubuntu@<服务器IP>
#
# 初始化完成后 → 部署系统见 ../docker/README.md
# ============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ARG_IP="${1:-}"
ARG_USER="${2:-}"
# 云密钥：环境变量优先于 server.env（source 前备份，source 后恢复非空值）
_ENV_SG_ID="${TENCENTCLOUD_SECRET_ID:-}"
_ENV_SG_KEY="${TENCENTCLOUD_SECRET_KEY:-}"
_ENV_SG_REGION="${TENCENTCLOUD_REGION:-}"
if [ -f "$SCRIPT_DIR/../docker/server.env" ]; then . "$SCRIPT_DIR/../docker/server.env"; fi
[ -n "$_ENV_SG_ID" ]     && TENCENTCLOUD_SECRET_ID="$_ENV_SG_ID"
[ -n "$_ENV_SG_KEY" ]    && TENCENTCLOUD_SECRET_KEY="$_ENV_SG_KEY"
[ -n "$_ENV_SG_REGION" ] && TENCENTCLOUD_REGION="$_ENV_SG_REGION"
unset _ENV_SG_ID _ENV_SG_KEY _ENV_SG_REGION
SERVER_IP="${ARG_IP:-${SERVER_IP:?请传入服务器 IP 或先配置 ../docker/server.env}}"
SERVER_USER="${ARG_USER:-${SERVER_USER:-ubuntu}}"
SSH_TARGET="${SERVER_USER}@${SERVER_IP}"

RED='\033[0;31m'; GREEN='\033[0;32m'; BLUE='\033[0;34m'; NC='\033[0m'
info()  { echo -e "${BLUE}[INFO]${NC}  $*"; }
ok()    { echo -e "${GREEN}[OK]${NC}    $*"; }
fail()  { echo -e "${RED}[FAIL]${NC}  $*"; exit 1; }

echo ""
echo "=========================================="
echo " 盘家智管 — 服务器初始化 → $SSH_TARGET"
echo "=========================================="

# ---- 前置检查 ----
ssh -o ConnectTimeout=8 -o BatchMode=yes "$SSH_TARGET" "echo ok" >/dev/null 2>&1 \
    || fail "SSH 连不上 ${SSH_TARGET}（先 ssh-copy-id）"
ok " SSH 连通"

# ==================== 步骤 0：云安全组自动放行 80/8080（腾讯云，本地执行） ====================
# 安全组是云平台 API 层的，服务器内部脚本改不了；这里通过 tccli 调云 API 自动补规则。
# 密钥来源：环境变量 TENCENTCLOUD_* > ../docker/server.env；不配置则跳过（回退手工控制台放行）。
SG_SECRET_ID="${TENCENTCLOUD_SECRET_ID:-}"
SG_SECRET_KEY="${TENCENTCLOUD_SECRET_KEY:-}"
SG_REGION="${TENCENTCLOUD_REGION:-}"
SG_TCCLI=""

ensure_tccli() {
    command -v tccli >/dev/null 2>&1 && { SG_TCCLI="tccli"; return 0; }
    info "  安装腾讯云 CLI（pip3 install --user tccli）..."
    pip3 install -q --user tccli 2>/dev/null \
        || pip3 install -q --user --break-system-packages tccli 2>/dev/null || true
    for c in "$HOME"/Library/Python/*/bin/tccli "$HOME/.local/bin/tccli"; do
        [ -x "$c" ] && { SG_TCCLI="$c"; return 0; }
    done
    return 1
}

if [ -n "$SG_SECRET_ID" ] && [ -n "$SG_SECRET_KEY" ] && [ -n "$SG_REGION" ]; then
    info ">>> 步骤 0/4：云安全组自动放行 22/80/8080（region: $SG_REGION）"
    if ! ensure_tccli; then
        warn "tccli 安装失败，请手工在控制台放行安全组 22/80/8080"
    else
        export TENCENTCLOUD_SECRET_ID="$SG_SECRET_ID" TENCENTCLOUD_SECRET_KEY="$SG_SECRET_KEY"
        export TENCENTCLOUD_REGION="$SG_REGION"
        # 按公网 IP 找 CVM 实例及其安全组
        SG_MATCHED=$("$SG_TCCLI" cvm DescribeInstances --limit 100 --output json 2>/dev/null | python3 -c "
import json, sys
try:
    for it in json.load(sys.stdin).get('InstanceSet', []):
        if '$SERVER_IP' in (it.get('PublicIpAddresses') or []):
            print(it['InstanceId'], ','.join(it.get('SecurityGroupIds') or []))
            break
except Exception:
    pass
")
        if [ -z "$SG_MATCHED" ]; then
            # CVM 没找到 → 尝试轻量应用服务器（Lighthouse，控制台里叫"实例防火墙"）
            LH_ID=$("$SG_TCCLI" lighthouse DescribeInstances --output json 2>/dev/null | python3 -c "
import json, sys
try:
    for it in json.load(sys.stdin).get('InstanceSet', []):
        ips = list(it.get('PublicAddresses') or []) + list(it.get('PublicIpAddresses') or [])
        if '$SERVER_IP' in ips:
            print(it['InstanceId'])
            break
except Exception:
    pass
")
            if [ -z "$LH_ID" ]; then
                warn "CVM/轻量服务器都未找到公网 IP 为 $SERVER_IP 的实例（核对 TENCENTCLOUD_REGION），请手工放行 22/80/8080"
            else
                ok " 识别为轻量应用服务器：$LH_ID"
                LH_INFO=$("$SG_TCCLI" lighthouse DescribeFirewallRules --InstanceId "$LH_ID" --output json 2>/dev/null | python3 -c "
import json, sys
try:
    d = json.load(sys.stdin)
    rules = [r for r in (d.get('FirewallRuleSet') or [])
             if (r.get('Action') or '') == 'ACCEPT' and (r.get('Protocol') or '').upper() == 'TCP']
    print(str(d.get('FirewallVersion') or 0), ' '.join(str(r.get('Port', '')) for r in rules))
except Exception:
    print('')
")
                LH_VER="${LH_INFO%% *}"
                HAVE=" ${LH_INFO#* } "
                MISS=""
                for P in 22 80 8080; do
                    echo "$HAVE" | grep -q " $P " || MISS="$MISS$P "
                done
                if [ -z "$MISS" ]; then
                    ok " 实例防火墙已放行 22/80/8080"
                else
                    info "  实例防火墙补放行:$MISS"
                    RULES=$(MISS_LIST="$MISS" python3 -c "
import json, os
rules = [{'Protocol': 'TCP', 'Port': p, 'CidrBlock': '0.0.0.0/0', 'Action': 'ACCEPT', 'FirewallRuleDescription': 'panjia-auto'} for p in os.environ['MISS_LIST'].split()]
print(json.dumps(rules))
")
                    if "$SG_TCCLI" lighthouse CreateFirewallRules --InstanceId "$LH_ID" --FirewallVersion "$LH_VER" --Rules "$RULES" >/dev/null 2>&1; then
                        ok " 实例防火墙已放行:$MISS"
                    else
                        warn "实例防火墙自动放行失败，请手工在控制台放行 22/80/8080"
                    fi
                fi
            fi
        else
            for SG in ${SG_MATCHED#* }; do
                HAVE=$("$SG_TCCLI" vpc DescribeSecurityGroupPolicies --SecurityGroupId "$SG" --output json 2>/dev/null | python3 -c "
import json, sys
try:
    ps = (json.load(sys.stdin).get('SecurityGroupPolicySet') or {}).get('Ingress') or []
    print(' '.join(str(p.get('Port', '')) for p in ps))
except Exception:
    print('')
")
                MISS=""
                for P in 22 80 8080; do
                    echo " $HAVE " | grep -q " $P " || MISS="$MISS$P "
                done
                if [ -z "$MISS" ]; then
                    ok " 安全组 $SG 已放行 22/80/8080"
                    continue
                fi
                info "  安全组 $SG 补放行:$MISS"
                RULES=$(MISS_LIST="$MISS" python3 -c "
import json, os
ingress = [{'Protocol': 'TCP', 'Port': p, 'CidrBlock': '0.0.0.0/0', 'PolicyDescription': 'panjia-auto'} for p in os.environ['MISS_LIST'].split()]
print(json.dumps([{'SecurityGroupPolicySet': {'Ingress': ingress}}]))
")
                if "$SG_TCCLI" vpc CreateSecurityGroupPolicies --SecurityGroupId "$SG" --SecurityGroupPolicies "$RULES" >/dev/null 2>&1; then
                    ok " 安全组 $SG 已放行:$MISS"
                else
                    warn "安全组 $SG 自动放行失败，请手工在控制台放行 80/8080"
                fi
            done
        fi
    fi
else
    info ">>> 步骤 0/4：未配置腾讯云 API 密钥（server.env 的 TENCENTCLOUD_*），安全组需手工放行 22/80/8080"
fi

info ">>> 步骤 1/4：系统基础（apt 工具 + 时区）"
ssh -o ServerAliveInterval=30 "$SSH_TARGET" sudo bash << 'REMOTE'
set -e
export DEBIAN_FRONTEND=noninteractive

apt-get update -qq
apt-get install -y -qq ca-certificates curl gnupg rsync ufw >/dev/null
echo "[服务器] 基础工具就绪"

# 时区（幂等）
timedatectl set-timezone Asia/Shanghai 2>/dev/null && echo "[服务器] 时区已设为 Asia/Shanghai" || true
REMOTE
ok " 系统基础完成"

info ">>> 步骤 2/4：防火墙 ufw（先放行 SSH 再启用，防锁死）"
ssh "$SSH_TARGET" sudo bash << 'REMOTE'
set -e
# 幂等：已启用则只补规则
if ! ufw status | grep -q "Status: active"; then
    ufw allow 22/tcp  >/dev/null   # SSH（必须先于 enable）
    ufw --force enable >/dev/null
    echo "[服务器] ufw 已启用"
else
    echo "[服务器] ufw 已在运行"
fi
ufw allow 22/tcp   >/dev/null      # SSH
ufw allow 80/tcp   >/dev/null      # 前端 + API 入口（nginx）
ufw allow 8080/tcp >/dev/null      # 后端直连（调试用）
echo "[服务器] 放行规则：22 / 80 / 8080"
ufw status numbered | head -20
REMOTE
ok " 防火墙完成"

info ">>> 步骤 3/4：安装 Docker CE + compose 插件（腾讯云内网源优先）"
ssh "$SSH_TARGET" sudo bash << 'REMOTE'
set -e
export DEBIAN_FRONTEND=noninteractive

if command -v docker >/dev/null 2>&1 && docker compose version >/dev/null 2>&1; then
    echo "[服务器] Docker 已安装：$(docker --version)，跳过"
else
    install -d -m 0755 /etc/apt/keyrings
    # 探测腾讯云内网源（CVM 必通），不通回退官方源
    APT_BASE="https://download.docker.com/linux/ubuntu"
    if curl -s -m 3 -o /dev/null "https://mirrors.cloud.tencentyun.com/docker-ce/linux/ubuntu/dists"; then
        APT_BASE="https://mirrors.cloud.tencentyun.com/docker-ce/linux/ubuntu"
        echo "[服务器] 使用腾讯云内网 apt 源"
    else
        echo "[服务器] 内网源不可达，使用官方 apt 源"
    fi
    curl -fsSL "$APT_BASE/gpg" -o /etc/apt/keyrings/docker.asc
    chmod a+r /etc/apt/keyrings/docker.asc
    echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] $APT_BASE $(. /etc/os-release && echo "$VERSION_CODENAME") stable" \
        > /etc/apt/sources.list.d/docker.list
    apt-get update -qq
    apt-get install -y -qq docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin >/dev/null
    echo "[服务器] Docker 安装完成：$(docker --version)"
fi

systemctl enable --now docker >/dev/null 2>&1 || true
# 当前 SSH 用户加入 docker 组（重新登录后可直接跑 docker 命令，幂等）
usermod -aG docker "$SUDO_USER" 2>/dev/null || true
REMOTE
ok " Docker 就绪"

info ">>> 步骤 4/4：Docker 加速源（腾讯云内网 registry 源优先）"
ssh "$SSH_TARGET" sudo bash << 'REMOTE'
set -e
# 幂等：deploy.sh 也有同样逻辑，两边内容保持一致
if ! grep -q registry-mirrors /etc/docker/daemon.json 2>/dev/null; then
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
docker info 2>/dev/null | grep -A5 "Registry Mirrors" || true
REMOTE
ok " Docker 加速源完成"

echo ""
echo "=========================================="
ok " 服务器初始化完成！"
echo "  下一步部署系统："
echo "    cd ../docker && bash deploy.sh $SERVER_IP"
echo "  文档：../docker/README.md"
echo "=========================================="
