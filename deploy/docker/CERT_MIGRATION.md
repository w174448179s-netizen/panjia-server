# 授权服务器证书与 License 指纹迁移指南

适用场景：把 **panjia.online**（主站 + console 授权服务）从一台服务器迁移到另一台。

---

## 一、为什么迁移时必须带上证书私钥

License 客户端启用了 SSL Pinning，校验对象是授权服务器证书**公钥（SPKI）**的 SHA-256：

- 当前指纹：`5f9a4d5dda12751bfa7a7d5fd490a83d5ae0e6aae5abc6eb06a64e21a41e94ce`
- 客户端实现：`panjia-modules/panjia-license/.../PinnedTrustManager.java`
  （`SHA-256(cert.getPublicKey().getEncoded())`，主备双指纹）

因此：

| 迁移方式 | 公钥 | 客户端影响 |
|---|---|---|
| 带上旧 `/etc/letsencrypt`（含 `privkey.pem`），续签复用密钥 | 不变 | 无感，无需改任何客户配置 |
| 新机器重新申请证书（没有旧私钥） | **变了** | 全部客户端 pin 失配 → 3 次心跳失败进入 `OFFLINE_GRACE`，7 天后 `OFFLINE_LOCK` |

> 注意：客户服务器自己的 HTTPS 域名证书与本 pin 无关，只有**授权服务器**（panjia.online）的密钥才会影响 License。

---

## 二、备份包

已生成的备份（2026-10-21）：

| 位置 | 文件 | 说明 |
|---|---|---|
| 授权服务器 | `/home/ubuntu/backup/letsencrypt-panjia.online-20261021-200653.tar.gz` | 权限 600 |
| 本地（Shengwei Mac） | `~/panjia-backup/letsencrypt-panjia.online-20261021-200653.tar.gz` | 权限 600 |

- SHA-256：`03ffc5ab5f4b96c021b5d3c2f4d0100a7946880010dff9a86b81078d42c6cf25`
- 包内容：`/etc/letsencrypt` 全量，含 `live/`、`archive/`（**私钥**）、`renewal/`、`renewal-hooks/`、`accounts/`

重新打包（建议每次证书续签后刷新一份）：

```bash
sudo mkdir -p /home/ubuntu/backup && sudo chmod 700 /home/ubuntu/backup
sudo tar czf /home/ubuntu/backup/letsencrypt-panjia.online-$(date +%Y%m%d-%H%M%S).tar.gz -C / etc/letsencrypt
sudo chmod 600 /home/ubuntu/backup/letsencrypt-panjia.online-*.tar.gz
sha256sum /home/ubuntu/backup/letsencrypt-panjia.online-*.tar.gz
```

**该包内含私钥，禁止提交到 git，禁止放在公开目录。**

---

## 三、迁移步骤（新服务器）

> 前提：新机已按标准流程装好 Docker 与业务栈（`deploy/docker/deploy.sh`），业务目录默认 `/home/ubuntu/panjia`。

### 1. 上传并解压备份包

```bash
# 本机执行
scp ~/panjia-backup/letsencrypt-panjia.online-20261021-200653.tar.gz ubuntu@<新服务器IP>:/tmp/

# 新服务器执行（必须 root，保留属主与符号链接）
sudo tar xzf /tmp/letsencrypt-panjia.online-20261021-200653.tar.gz -C /
```

解压后确认私钥存在且为符号链接：

```bash
sudo ls -l /etc/letsencrypt/live/panjia.online/privkey.pem
# 应指向 ../../archive/panjia.online/privkeyN.pem
```

### 2. 校验公钥指纹（关键，必须仍是 5f9a...94ce）

```bash
sudo openssl x509 -in /etc/letsencrypt/live/panjia.online/cert.pem -pubkey -noout \
  | openssl pkey -pubin -outform der | sha256sum
```

### 3. 修正 renewal 配置

```bash
sudo grep -E "^(authenticator|webroot_path|key_type|reuse_key)" \
  /etc/letsencrypt/renewal/panjia.online.conf
```

期望值：

```
authenticator = webroot
key_type = ecdsa
reuse_key = True
webroot_path = /home/ubuntu/panjia/acme,
```

若新机的业务目录不是 `/home/ubuntu/panjia`，把 `webroot_path` 改成实际路径（保留行尾逗号）。

### 4. 安装续签钩子

备份包已包含钩子（`/etc/letsencrypt/renewal-hooks/deploy/panjia-license-pin.sh`），检查其中两处目录与新机一致：

```bash
sudo grep -n "/home/ubuntu/panjia" /etc/letsencrypt/renewal-hooks/deploy/panjia-license-pin.sh
sudo chmod 755 /etc/letsencrypt/renewal-hooks/deploy/panjia-license-pin.sh
```

若目录不同，改脚本里的 `for dir in /home/ubuntu/panjia /home/ubuntu/panjia-test`。

### 5. 恢复定时任务

```bash
# 每天 03:00 续签 + reload nginx
sudo tee /etc/cron.d/panjia-certbot >/dev/null <<'EOF'
0 3 * * * root certbot renew --webroot -w /home/ubuntu/panjia/acme --quiet && docker exec panjia-web nginx -s reload 2>/dev/null || true
EOF

# 每天 04:20 兜底：证书换了但 pin 没跟上时补齐
sudo tee /etc/cron.d/panjia-license-pin >/dev/null <<'EOF'
20 4 * * * root /etc/letsencrypt/renewal-hooks/deploy/panjia-license-pin.sh >/dev/null 2>&1
EOF

sudo chmod 644 /etc/cron.d/panjia-certbot /etc/cron.d/panjia-license-pin
```

> 单机多栈（生产 + 测试）时，脚本会同时更新两个 `.env` 并重启两个 `server`；只有一套栈时，不存在的目录会被自动跳过。

### 6. 创建 ACME 目录并演练

```bash
mkdir -p /home/ubuntu/panjia/acme
sudo certbot renew --cert-name panjia.online --dry-run --no-random-sleep-on-renew
```

看到 `Congratulations, all simulated renewals succeeded` 即通过。

### 7. 切换 DNS 并保留旧机

1. 把 `panjia.online` / `www.panjia.online` 的 A 记录指向新机；
2. 旧机**不要立即关机**，至少保留到 DNS TTL 全部过期并确认新机心跳正常；
3. 旧机保留期间不要再单独续签或重建证书（两边私钥相同，续签也只会覆盖本机文件，不影响新机）。

### 8. 迁移后验证

```bash
# 公钥指纹
sudo openssl x509 -in /etc/letsencrypt/live/panjia.online/cert.pem -pubkey -noout \
  | openssl pkey -pubin -outform der | sha256sum

# 站点
curl -s -o /dev/null -w "%{http_code}\n" https://panjia.online/
curl -s -o /dev/null -w "%{http_code}\n" https://panjia.online/console/     # 期望 401

# 授权状态（业务后端日志）
sudo docker logs panjia-server --since 10m 2>&1 | grep -iE "SSL Pinning|心跳成功|授权状态"
# 期望：主指纹=5f9a****94ce，心跳成功，clientMode=NORMAL
```

---

## 四、如果拿不到旧私钥

只能换钥匙，后果是所有已部署客户端的 pin 失效。处理顺序：

1. 新机正常签发证书，计算新的 SPKI 指纹；
2. 更新 `LicenseProperties.java`、`application.yml`、`docker-compose.yml`、`deploy.sh`、`.env.example` 中的默认指纹；
3. 重新构建镜像并发布，**所有客户服务器**升级（或手工改各自 `.env` 的 `PANJIA_PIN_FP_A`）；
4. 过渡期可先把新指纹写入 `PANJIA_PIN_FP_B` 发一版，让客户端同时信任新旧公钥，确认全部升级后再切换主备。

未升级的客户端会在证书切换后 3 次心跳失败进入宽限期，7 天后锁死。

---

## 五、常见坑

- ❌ 在新机上直接 `certbot certonly` 重新申请：会生成新密钥，指纹立刻变化。
- ❌ 只拷贝 `live/` 目录、丢了 `archive/`：`live` 是符号链接，私钥实际在 `archive/`。
- ❌ 只拷贝证书不拷贝 `renewal/`：新机续签会退回默认配置（standalone + 新密钥）。
- ❌ 把备份包提交到 git 或放进 Web 目录。
- ❌ 迁移前执行 `certbot delete` / `revoke`。
- ⚠ 证书续签后 `archive/` 会新增文件，旧备份包仍可用（私钥不变），但建议定期刷新备份。
- ⚠ 钩子脚本里的部署路径是写死的，迁移到不同目录必须同步修改。
