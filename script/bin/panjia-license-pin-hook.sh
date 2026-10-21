#!/bin/bash
# ============================================================================
# certbot 部署钩子：panjia.online 证书续签后自动同步 License SSL Pinning 指纹
#
# 背景：
#   License 客户端校验的是叶子证书公钥（SPKI）的 SHA-256（PinnedTrustManager）。
#   certbot 配置 reuse_key 后续签复用同一密钥，公钥不变，指纹稳定，正常续签无需处理；
#   只有密钥发生轮换（换 key / 重建证书）时指纹才会变化，此时若不更新 pin，
#   心跳会失败，连续 3 次进入 OFFLINE_GRACE，7 天后 OFFLINE_LOCK。
#
# 本钩子在续签成功后：
#   1. 计算新证书公钥(SPKI)指纹（与 Java 端 cert.getPublicKey().getEncoded() 的 SHA-256 一致）
#   2. 指纹未变化（复用密钥）→ 直接退出，不 reload、不重启
#   3. 指纹变化 → 先 reload 主站 nginx，让新证书立即生效
#   4. 把旧主指纹移到备指纹，写入生产和测试的 .env
#   5. 重启两个环境的 server 容器，让新 pin 生效
#
# 安装：
#   install -m 755 panjia-license-pin-hook.sh \
#       /etc/letsencrypt/renewal-hooks/deploy/panjia-license-pin.sh
#
# 说明：
#   - 只处理 RENEWED_LINEAGE 为 panjia.online 的续签，其他证书直接跳过
#   - 只覆盖本机的 /home/ubuntu/panjia 和 /home/ubuntu/panjia-test
#   - 其他远程客户服务器仍需在部署时同步 pin
#   - --check 只计算并打印，不写文件、不重启，便于人工验证
# ============================================================================
set -uo pipefail

LOG_FILE="/var/log/panjia-license-pin.log"
LOCK_FILE="/var/lock/panjia-license-pin.lock"
CERT_DIR="${RENEWED_LINEAGE:-/etc/letsencrypt/live/panjia.online}"
CERT_FILE="${CERT_DIR}/fullchain.pem"
CHECK_ONLY=0
[ "${1:-}" = "--check" ] && CHECK_ONLY=1

log() {
    echo "$(date '+%F %T') $*" >> "${LOG_FILE}" 2>/dev/null || true
}

# 非 panjia.online 证书不处理
case "${CERT_DIR}" in
    *panjia.online*) ;;
    *) exit 0 ;;
esac

# 防止并发执行
if command -v flock >/dev/null 2>&1; then
    exec 9>"${LOCK_FILE}" || exit 0
    flock -n 9 || { log "已有实例在运行，跳过"; exit 0; }
fi

if [ ! -f "${CERT_FILE}" ]; then
    log "证书不存在: ${CERT_FILE}，跳过"
    exit 0
fi

NEW_FP=$(openssl x509 -in "${CERT_FILE}" -pubkey -noout 2>/dev/null \
           | openssl pkey -pubin -outform der 2>/dev/null \
           | sha256sum | awk '{print $1}')
if [ "${#NEW_FP}" -ne 64 ]; then
    log "计算证书指纹失败: ${CERT_FILE}"
    exit 1
fi

if [ "${CHECK_ONLY}" -eq 1 ]; then
    echo "证书: ${CERT_FILE}"
    echo "当前指纹: ${NEW_FP}"
    for dir in /home/ubuntu/panjia /home/ubuntu/panjia-test; do
        env_file="${dir}/.env"
        if [ -f "${env_file}" ]; then
            printf "%s PANJIA_PIN_FP_A=%s PANJIA_PIN_FP_B=%s\n" \
                "${dir}" \
                "$(grep -E '^PANJIA_PIN_FP_A=' "${env_file}" | tail -1 | cut -d= -f2-)" \
                "$(grep -E '^PANJIA_PIN_FP_B=' "${env_file}" | tail -1 | cut -d= -f2-)"
        else
            echo "${dir} 缺少 .env"
        fi
    done
    exit 0
fi

reload_nginx() {
    if ! docker exec panjia-web nginx -t >/dev/null 2>&1; then
        log "nginx -t 失败，终止（保持原 pin，避免客户端无法握手）"
        return 1
    fi
    if ! docker exec panjia-web nginx -s reload >>"${LOG_FILE}" 2>&1; then
        log "nginx reload 失败，终止（保持原 pin）"
        return 1
    fi
    sleep 2
}

# 真实续签（certbot 会传 RENEWED_LINEAGE）后总是 reload nginx，让新证书立即生效。
# 复用密钥时指纹不变，reload 就够了，不需要改配置、也不需要重启服务。
if [ -n "${RENEWED_LINEAGE:-}" ]; then
    reload_nginx || exit 1
    log "续签后 nginx 已 reload（公钥指纹 ${NEW_FP}）"
fi

# 指纹已一致时直接退出，不重启任何容器（供每日兜底检查复用）
needs_update=0
for dir in /home/ubuntu/panjia /home/ubuntu/panjia-test; do
    env_file="${dir}/.env"
    [ -f "${env_file}" ] || continue
    current=$(grep -E '^PANJIA_PIN_FP_A=' "${env_file}" | tail -1 | cut -d= -f2-)
    [ "${current}" = "${NEW_FP}" ] || needs_update=1
done
if [ "${needs_update}" -eq 0 ]; then
    log "生产/测试指纹均已是最新（${NEW_FP}），无需重启"
    exit 0
fi

# 兜底补指纹（证书已换但 pin 未跟上）时，同样先确保 nginx 在用新证书
reload_nginx || exit 1

update_env() {
    local dir="$1"
    local env_file="${dir}/.env"
    if [ ! -f "${env_file}" ]; then
        log "缺少 ${env_file}，跳过"
        return 1
    fi

    local old_primary old_secondary
    old_primary=$(grep -E '^PANJIA_PIN_FP_A=' "${env_file}" | tail -1 | cut -d= -f2-)
    old_secondary=$(grep -E '^PANJIA_PIN_FP_B=' "${env_file}" | tail -1 | cut -d= -f2-)

    if [ "${old_primary}" = "${NEW_FP}" ]; then
        log "${dir} 已是最新指纹，跳过"
        return 0
    fi

    cp -a "${env_file}" "${env_file}.bak-pin-$(date +%Y%m%d-%H%M%S)"
    if grep -q '^PANJIA_PIN_FP_A=' "${env_file}"; then
        sed -i "s|^PANJIA_PIN_FP_A=.*|PANJIA_PIN_FP_A=${NEW_FP}|" "${env_file}"
    else
        echo "PANJIA_PIN_FP_A=${NEW_FP}" >> "${env_file}"
    fi
    if grep -q '^PANJIA_PIN_FP_B=' "${env_file}"; then
        sed -i "s|^PANJIA_PIN_FP_B=.*|PANJIA_PIN_FP_B=${old_primary}|" "${env_file}"
    else
        echo "PANJIA_PIN_FP_B=${old_primary}" >> "${env_file}"
    fi

    if ! ( cd "${dir}" && docker compose up -d server ) >>"${LOG_FILE}" 2>&1; then
        log "${dir} server 重启失败，请人工检查"
        return 1
    fi
    log "${dir} 已更新: A=${NEW_FP} B=${old_primary:-<空>}（原备指纹 ${old_secondary:-<空>}）"
    return 0
}

rc=0
update_env /home/ubuntu/panjia || rc=1
update_env /home/ubuntu/panjia-test || rc=1
exit "${rc}"
