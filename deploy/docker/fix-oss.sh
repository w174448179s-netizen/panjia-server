#!/usr/bin/env bash
# =============================================================================
# Panjia OSS 一键配置脚本（新服务器接入 / 故障自愈通用）
#
# 用法：cd 到部署目录（含 docker-compose.yml 与 .env）后执行
#       bash fix-oss.sh
# 作用域：只影响当前目录对应的 compose 项目（多环境同机部署互不影响，
#         容器一律通过 `docker compose ps -q <服务名>` 定位，严禁写死容器名）
# 原理与常见坑说明：见同目录 OSS_SETUP.md
# =============================================================================
set -u
cd "$(dirname "$0")" || exit 1

[ -f .env ] || { echo "[X] 当前目录没有 .env——必须先 cd 到部署目录"; exit 1; }
source .env
DC="docker compose"; docker compose version >/dev/null 2>&1 || DC="docker-compose"

cid() { $DC ps -q "$1" 2>/dev/null | head -1; }
PG_C=$(cid postgres); MINIO_C=$(cid minio); REDIS_C=$(cid redis); WEB_C=$(cid web)
[ -n "$PG_C" ] && [ -n "$REDIS_C" ] && [ -n "$WEB_C" ] || {
  echo "[X] postgres/redis/web 容器未运行，请先 docker compose up -d"; exit 1; }
DBN="${POSTGRES_DB:-panjia}"
PG()   { docker exec "$PG_C" psql -U "${POSTGRES_USER:?缺 POSTGRES_USER}" -d "$DBN" -c "$1"; }
PGSEL(){ docker exec "$PG_C" psql -U "${POSTGRES_USER:?缺 POSTGRES_USER}" -d "$DBN" -tAc "$1"; }

echo "== 1/7 检查 MinIO =="
if [ -n "$MINIO_C" ]; then
  for i in $(seq 1 12); do
    docker exec "$MINIO_C" curl -sf http://127.0.0.1:9000/minio/health/live >/dev/null 2>&1 && break; sleep 5
  done
  docker exec "$MINIO_C" curl -sf http://127.0.0.1:9000/minio/health/live >/dev/null \
    || { echo "[X] 内置 MinIO 未就绪，docker logs 看容器日志"; exit 1; }
  echo "OK（内置 MinIO 运行中）"
else
  echo "外部 MinIO 模式（COMPOSE_PROFILES 未启用 embedded-minio）"
fi

echo "== 2/7 读取 MinIO 实际密钥（以运行中容器的环境变量为准，勿手抄 .env） =="
if [ -n "$MINIO_C" ]; then
  MU=$(docker inspect "$MINIO_C" --format '{{range .Config.Env}}{{println .}}{{end}}' | sed -n 's/^MINIO_ROOT_USER=//p')
  MP=$(docker inspect "$MINIO_C" --format '{{range .Config.Env}}{{println .}}{{end}}' | sed -n 's/^MINIO_ROOT_PASSWORD=//p')
  ENDPOINT="minio:9000"
else
  MU="${PANJIA_IMPORT_MINIO_ACCESS_KEY:?外部模式需在 .env 配置 PANJIA_IMPORT_MINIO_ACCESS_KEY}"
  MP="${PANJIA_IMPORT_MINIO_SECRET_KEY:?外部模式需在 .env 配置 PANJIA_IMPORT_MINIO_SECRET_KEY}"
  ENDPOINT="${PANJIA_IMPORT_MINIO_ENDPOINT#http://}"; ENDPOINT="${ENDPOINT#https://}"
fi
[ -n "$MU" ] || { echo "[X] 未能读到 MinIO 账号"; exit 1; }
echo "账号=$MU  endpoint=$ENDPOINT"

echo "== 3/7 确保桶存在 =="
BUCKET=$(PGSEL "select bucket_name from sys_oss_config where config_key='minio'")
BUCKET="${BUCKET:-panjia-file}"
if [ -n "$MINIO_C" ]; then
  if docker exec "$MINIO_C" sh -c 'command -v mc >/dev/null' 2>/dev/null; then
    docker exec "$MINIO_C" sh -c "mc alias set pj http://127.0.0.1:9000 '$MU' '$MP' >/dev/null && mc mb --ignore-existing pj/$BUCKET" \
      && echo "桶 $BUCKET OK" || echo "(建桶失败，若上传报 NoSuchBucket 请手动建桶)"
  else
    echo "(容器内无 mc 工具，跳过建桶；若上传报 NoSuchBucket 请手动建桶)"
  fi
fi

echo "== 4/7 回填 sys_oss_config（浏览器回显域名 = 本机IP:WEB端口/oss） =="
IP=$(curl -s --max-time 5 ifconfig.me | tr -d '[:space:]'); [ -n "$IP" ] || IP=$(hostname -I | awk '{print $1}')
PORT=$(docker port "$WEB_C" 80/tcp 2>/dev/null | head -1 | sed 's/.*://'); PORT="${PORT:-80}"
DOMAIN="$IP:$PORT/oss"; [ "$PORT" = "80" ] && DOMAIN="$IP/oss"
PG "update sys_oss_config set endpoint='$ENDPOINT', access_key='$MU', secret_key='$MP', domain_url='$DOMAIN', update_time=now() where config_key='minio'"
PG "select config_key, endpoint, domain_url, bucket_name, access_key from sys_oss_config where config_key='minio'"

echo "== 5/7 重建存量图片 URL（坏域名存量数据一次性修复） =="
PG "update sys_oss set url='http://$DOMAIN/$BUCKET/'||file_name where service='minio'"

echo "== 6/7 修补 nginx /oss/ 代理（整块替换为标准块，幂等可重复执行） =="
NGINX_CONF=$(docker inspect "$WEB_C" --format '{{range .Mounts}}{{if eq .Destination "/etc/nginx/nginx.conf"}}{{.Source}}{{end}}{{end}}')
if [ -n "$NGINX_CONF" ] && [ -f "$NGINX_CONF" ]; then
  cp "$NGINX_CONF" "$NGINX_CONF.bak.$(date +%s)"
  python3 - "$NGINX_CONF" <<'PY'
import sys
p = sys.argv[1]
lines = open(p).read().split('\n')
block = [
    '        # MinIO 图片/文件直链（sys_oss_config.domain_url = http://服务器IP/oss）',
    '        # set 必须在 rewrite break 之前，否则变量赋值被跳过 → proxy_pass 空值 → 500',
    '        location /oss/ {',
    '            resolver 127.0.0.11 valid=10s ipv6=off;',
    '            set $minio_upstream http://minio:9000;',
    '            rewrite ^/oss/(.*)$ /$1 break;',
    '            proxy_pass $minio_upstream;',
    '            proxy_set_header Host $http_host;',
    '            proxy_set_header X-Real-IP $remote_addr;',
    '            proxy_read_timeout 60s;',
    '        }',
]
out, i, replaced = [], 0, False
while i < len(lines):
    L = lines[i]
    if L.strip().startswith('location /oss/'):
        # 整块替换到本块闭合 '}'（块内无嵌套花括号）
        while i < len(lines) and lines[i].strip() != '}':
            i += 1
        out.extend(block); replaced = True; i += 1
        continue
    out.append(L); i += 1
if not replaced:
    out2 = []
    for L in out:
        if L.rstrip().endswith('location / {') and L.startswith('    '):
            out2.extend(block)
        out2.append(L)
    out = out2
    replaced = True
open(p, 'w').write('\n'.join(out))
print('/oss/ 块已整块替换为标准版')
PY
  if docker exec "$WEB_C" nginx -t 2>&1 && docker exec "$WEB_C" nginx -s reload; then
    echo "nginx 已热加载"
  else
    BAK=$(ls -t "$NGINX_CONF".bak.* 2>/dev/null | head -1)
    [ -n "$BAK" ] && cp "$BAK" "$NGINX_CONF" && docker exec "$WEB_C" nginx -s reload
    echo "[X] nginx 校验失败，已还原备份，请人工检查"; exit 1
  fi
else
  echo "(未找到 nginx.conf 挂载，跳过 nginx 修补)"
fi

echo "== 7/7 重启后端、清缓存、自检 =="
$DC restart server
for i in $(seq 1 60); do
  CODE=$(curl -s -o /dev/null -w '%{http_code}' --max-time 3 "http://127.0.0.1:$PORT/prod-api/" 2>/dev/null)
  [ "$CODE" != "000" ] && [ "$CODE" != "502" ] && break
  sleep 5
done
echo "后端就绪（HTTP $CODE）"
docker exec "$REDIS_C" sh -c 'redis-cli -a "$REDIS_PASSWORD" DEL sys_oss' >/dev/null 2>&1
echo -n "图片通道自检 /oss/minio/health/live: "
OSSCHK=$(curl -s -o /dev/null -w '%{http_code} %{content_type}' --max-time 8 "http://127.0.0.1:$PORT/oss/minio/health/live")
echo "$OSSCHK"
case "$OSSCHK" in
  "200 text/html"*) echo "[X] /oss/ 落到了前端 SPA fallback——nginx.conf 里没有 /oss/ location，人工检查第 6 步";;
  200*)             echo "[OK] 全链路就绪。domain_url=http://$DOMAIN  页面重新登录后上传/回显验证即可。";;
  *)                echo "[X] /oss/ 通道异常（$OSSCHK）——docker compose logs web 查看 nginx 错误";;
esac
