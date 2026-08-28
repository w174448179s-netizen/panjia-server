#!/bin/bash
#
# 盘家 License RSA 密钥对生成脚本
#
# 用途：
#   1. 首次生成：生成 RSA 2048 密钥对，导出公钥证书到 panjia-license 模块
#   2. 密钥轮换：生成新密钥对（v2, v3...），旧 token 继续有效，新 token 用新密钥签发
#
# 产出文件：
#   - script/keys/panjia-license.jks          ← 私钥库（授权服务器用，不入 Git）
#   - panjia-modules/panjia-license/src/main/resources/license-public-key.pem  ← 公钥证书（打包进客户端 JAR）
#
# 用法：
#   首次生成：  ./generate-license-key.sh
#   密钥轮换：  ./generate-license-key.sh v2
#
set -e

# --- 配置 ---
KEYSTORE=panjia-license.jks
STOREPASS="${LICENSE_KEYSTORE_PASS:-panjia2026!}"
ALIAS="${2:-1}"                          # 默认 alias=1，轮换时传 v2/v3
DNAME="CN=panjia-license, OU=panjia, O=panjia, L=Shanghai, C=CN"
VALIDITY=36500                           # 100 年（避免证书过期问题）

# 路径
KEYS_DIR="$(cd "$(dirname "$0")" && pwd)"
MODULE_DIR="$(cd "$(dirname "$0")/../../panjia-modules/panjia-license" && pwd)"
PEM_FILE="${MODULE_DIR}/src/main/resources/license-public-key.pem"

cd "${KEYS_DIR}"

echo "=========================================="
echo "  盘家 License 密钥生成"
echo "=========================================="
echo "  KeyStore:  ${KEYS_DIR}/${KEYSTORE}"
echo "  Alias:     ${ALIAS}"
echo "  公钥输出:   ${PEM_FILE}"
echo "  有效期:     ${VALIDITY} 天"
echo "=========================================="
echo ""

# --- 1. 生成 RSA 密钥对 ---
if keytool -list -keystore "${KEYSTORE}" -storepass "${STOREPASS}" -alias "${ALIAS}" >/dev/null 2>&1; then
    echo "[WARN] alias '${ALIAS}' 已存在，跳过生成。如需重新生成请先删除。"
else
    echo "[1/3] 生成 RSA 2048 密钥对（alias=${ALIAS}）..."
    keytool -genkeypair \
        -keystore "${KEYSTORE}" \
        -storepass "${STOREPASS}" \
        -alias "${ALIAS}" \
        -keyalg RSA \
        -keysize 2048 \
        -sigalg SHA256withRSA \
        -dname "${DNAME}" \
        -validity "${VALIDITY}" \
        -ext KeyUsage=digitalSignature \
        -ext ExtendedKeyUsage=codeSigning
    echo "[1/3] 完成。"
fi

# --- 2. 导出公钥证书 ---
echo "[2/3] 导出公钥证书（PEM 格式）..."
if [ "${ALIAS}" = "1" ]; then
    # 首次生成：直接覆盖默认公钥文件
    keytool -exportcert \
        -keystore "${KEYSTORE}" \
        -storepass "${STOREPASS}" \
        -alias "${ALIAS}" \
        -file "${PEM_FILE}" \
        -rfc
    echo "[2/3] 公钥已写入: ${PEM_FILE}"
else
    # 密钥轮换：写入带版本号的文件
    VERSIONED_PEM="${MODULE_DIR}/src/main/resources/license-public-key-${ALIAS}.pem"
    keytool -exportcert \
        -keystore "${KEYSTORE}" \
        -storepass "${STOREPASS}" \
        -alias "${ALIAS}" \
        -file "${VERSIONED_PEM}" \
        -rfc
    echo "[2/3] 公钥已写入: ${VERSIONED_PEM}"
    echo ""
    echo "  ⚠️  密钥轮换注意事项："
    echo "    1. 授权服务器使用新 alias 的私钥签发新 token"
    echo "    2. 新 token JWT header 需带 kid=${ALIAS}"
    echo "    3. 旧 token 继续用旧公钥验签（旧 .pem 保留在 JAR 内）"
    echo "    4. 等旧 token 全部过期后，删除旧 .pem 文件重新打包"
fi

# --- 3. 验证 ---
echo "[3/3] 验证密钥对..."
echo ""
echo "  KeyStore 内容："
keytool -list -keystore "${KEYSTORE}" -storepass "${STOREPASS}" 2>&1 | grep -A2 "Alias"
echo ""
echo "  公钥证书指纹："
keytool -exportcert \
    -keystore "${KEYSTORE}" \
    -storepass "${STOREPASS}" \
    -alias "${ALIAS}" \
    -rfc \
    | openssl x509 -fingerprint -sha256 -noout 2>/dev/null || echo "  (openssl 不可用，跳过指纹)"
echo ""
echo "=========================================="
echo "  ✅ 密钥生成完成"
echo "=========================================="
echo ""
echo "  安全提醒："
echo "    - ${KEYSTORE} 包含私钥，绝对不能提交到 Git"
echo "    - 部署授权服务器时手动拷贝此文件"
echo "    - 建议备份到安全的位置（如密码管理器）"
echo ""
echo "  后续操作："
echo "    - 授权服务器配置此 jks 文件路径和密码"
echo "    - 客户端 JAR 内已包含对应公钥 .pem 文件"
