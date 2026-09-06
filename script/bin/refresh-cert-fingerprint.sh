#!/bin/bash
# ---------------------------------------------------------------------------
# 证书指纹刷新工具
# 用途：获取授权服务器当前 SSL 证书的 SHA-256 指纹（小写无冒号），
#       对比 LicenseProperties 中已配置的主/备指纹，生成续签操作建议。
#
# 使用场景：
#   1. 证书续签后，确认新指纹是否与代码中配置的一致
#   2. 取新指纹填入 secondaryFingerprint 做双指纹过渡
#   3. 切换主备后清空旧指纹
#
# 用法：
#   ./refresh-cert-fingerprint.sh                      # 默认 panjia.icu
#   ./refresh-cert-fingerprint.sh license.example.com  # 指定域名
#   ./refresh-cert-fingerprint.sh license.example.com 8443  # 指定端口
#
# 依赖：openssl（macOS/Linux 自带）
# ---------------------------------------------------------------------------
set -euo pipefail

DOMAIN="${1:-panjia.icu}"
PORT="${2:-443}"

# LicenseProperties 文件路径（相对项目根目录定位）
PROPS_FILE="$(cd "$(dirname "$0")/../.." && pwd)/panjia-modules/panjia-license/src/main/java/com/panjia/license/config/LicenseProperties.java"

echo "======================================================"
echo " License 证书指纹刷新工具"
echo " 目标服务器: ${DOMAIN}:${PORT}"
echo " 配置文件:   ${PROPS_FILE}"
echo "======================================================"
echo ""

# ---------- 1. 取服务器当前证书指纹 ----------
echo "[1/3] 正在获取服务器证书指纹..."

if ! command -v openssl >/dev/null 2>&1; then
    echo "  [错误] 未找到 openssl，请先安装"
    exit 1
fi

RAW_FP=$(echo | openssl s_client -connect "${DOMAIN}:${PORT}" -servername "${DOMAIN}" 2>/dev/null \
    | openssl x509 -fingerprint -sha256 -noout 2>/dev/null | cut -d= -f2)

if [ -z "${RAW_FP}" ]; then
    echo "  [错误] 无法获取证书指纹，可能原因："
    echo "         - 域名无法解析 / 端口不通 / 服务器未开启 TLS"
    echo "         - openssl s_client 连接失败"
    exit 1
fi

# 转小写、去掉冒号（PinnedTrustManager 期望的格式）
NEW_FP=$(echo "${RAW_FP}" | tr '[:upper:]' '[:lower:]' | tr -d ':')

echo "  服务器当前证书指纹（原始）：${RAW_FP}"
echo "  服务器当前证书指纹（配置）：${NEW_FP}"
echo ""

# ---------- 2. 对比 LicenseProperties 已配置的指纹 ----------
echo "[2/3] 对比 LicenseProperties 已配置指纹..."

if [ ! -f "${PROPS_FILE}" ]; then
    echo "  [警告] 配置文件不存在: ${PROPS_FILE}"
    echo "  跳过对比步骤"
else
    # 从 LicenseProperties.java 提取 primaryFingerprint 和 secondaryFingerprint 的值
    CURRENT_PRIMARY=$(grep -E 'primaryFingerprint\s*=' "${PROPS_FILE}" \
        | sed -E 's/.*"([^"]+)".*/\1/' | head -1)
    CURRENT_SECONDARY=$(grep -E 'secondaryFingerprint\s*=' "${PROPS_FILE}" \
        | sed -E 's/.*"([^"]*)".*/\1/' | head -1)

    echo "  当前 primaryFingerprint   = ${CURRENT_PRIMARY:-（未配置）}"
    echo "  当前 secondaryFingerprint = ${CURRENT_SECONDARY:-（空）}"
    echo ""

    if [ "${NEW_FP}" = "${CURRENT_PRIMARY}" ]; then
        echo "  [结果] 新指纹 == 主指纹，证书未变更，无需操作"
        echo ""
        echo "======================================================"
        echo " 无需续签，退出。"
        echo "======================================================"
        exit 0
    fi

    if [ -n "${CURRENT_SECONDARY}" ] && [ "${NEW_FP}" = "${CURRENT_SECONDARY}" ]; then
        echo "  [结果] 新指纹 == 备指纹，已进入过渡期，可执行切换"
        echo ""
        echo "  >>> 下一步操作 <<<"
        echo "  1. 将 secondaryFingerprint 值提升为 primaryFingerprint"
        echo "  2. 清空 secondaryFingerprint"
        echo "  3. 重新构建发布"
        echo ""
        echo "  参考命令（请人工核对后执行）："
        echo "  sed -i '' 's/primaryFingerprint = \"[^\"]*\"/primaryFingerprint = \"${NEW_FP}\"/' \"${PROPS_FILE}\""
        echo "  sed -i '' 's/secondaryFingerprint = \"[^\"]*\"/secondaryFingerprint = \"\"/' \"${PROPS_FILE}\""
        echo ""
        echo "======================================================"
        echo " 续签切换完成（待人工执行上述命令后重新构建）"
        echo "======================================================"
        exit 0
    fi

    echo "  [结果] 新指纹与主备均不匹配 → 证书已变更，需进入过渡期"
fi

echo ""

# ---------- 3. 输出续签操作建议 ----------
echo "[3/3] 续签操作建议（双指纹过渡四阶段）"
echo ""
echo "  ┌─ 阶段 1：预发布（客户端同时信任新旧证书）
  │   将以下值填入 LicenseProperties.java 的 secondaryFingerprint：
  │   ${NEW_FP}
  │
  ├─ 阶段 2：发布带双指纹的客户端
  │   重新构建并发布，确保所有客户端都更新到含双指纹的版本
  │
  ├─ 阶段 3：服务端切换到新证书
  │   服务器续签完成，新证书生效；旧客户端因含备指纹也能正常连接
  │
  └─ 阶段 4：清理旧指纹
      确认所有客户端都更新后：
      - 将 secondaryFingerprint 值提升为 primaryFingerprint
      - 清空 secondaryFingerprint
      - 重新构建发布
"
echo "  >>> 可直接执行的命令（请人工核对后执行）<<<"
echo ""
echo "  # 阶段 1：填入新指纹作为备指纹"
echo "  sed -i '' 's/secondaryFingerprint = \"[^\"]*\"/secondaryFingerprint = \"${NEW_FP}\"/' \"${PROPS_FILE}\""
echo ""
echo "  # 阶段 4：切换主备并清空（确认所有客户端更新后执行）"
echo "  sed -i '' 's/primaryFingerprint = \"[^\"]*\"/primaryFingerprint = \"${NEW_FP}\"/' \"${PROPS_FILE}\""
echo "  sed -i '' 's/secondaryFingerprint = \"[^\"]*\"/secondaryFingerprint = \"\"/' \"${PROPS_FILE}\""
echo ""
echo "======================================================"
echo " 当前服务器指纹：${NEW_FP}"
echo "======================================================"
