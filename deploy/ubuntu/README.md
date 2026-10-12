# 盘家智管 · Ubuntu 服务器从零上线（手把手）

一台全新/重置过的 Ubuntu 服务器到系统上线，**共 5 步**。前 3 步在本页（初始化），后 2 步见 [deploy/docker/README.md](../docker/README.md)。

```
① 云平台开端口 → ② 本地配免密 → ③ 初始化服务器 → ④ 部署系统 → ⑤ 验证访问
```

---

## 第 ① 步：云平台控制台（先做，不然 SSH 都连不上）

**1. 重置/重装系统**（如服务器是全新的可跳过）

- 腾讯云控制台 → 云服务器 CVM → 实例 → 勾选实例 → 更多操作 → **重装系统**
- 镜像选 **Ubuntu 22.04 / 24.04**（64 位），认证方式选**密码**并记下密码

**2. 安全组放行端口**（⚠️ 必做：安全组是云层防火墙，服务器内部脚本改不了）

安全组在**云 API 层**，两种方式二选一：

**方式 A（推荐）：配一次 API 密钥，脚本全自动放行 80**

```bash
# 腾讯云控制台 → 访问管理 CAM → API 密钥管理 → 新建子用户密钥
# 子用户权限（按机器类型给）：
#   云服务器 CVM      → QcloudCVMReadOnlyAccess + QcloudVPCFullAccess（操作安全组）
#   轻量应用服务器    → QcloudLighthouseFullAccess（操作实例防火墙）
# （不确定机器类型就三个都授权；安全起见不要用主账号密钥）
# 编辑 deploy/docker/server.env，填入：
TENCENTCLOUD_SECRET_ID=AKIDxxxxxxxx
TENCENTCLOUD_SECRET_KEY=xxxxxxxx
TENCENTCLOUD_REGION=ap-guangzhou      # 服务器所在地域，控制台实例详情能看到
```

配好后第 ③ 步的 `setup-server.sh` 会自动按公网 IP 识别机器类型（CVM 安全组 / 轻量服务器实例防火墙），幂等补放行 22/80（80 缺则补，22 也会确保存在）。
此文件已加 `.gitignore`，密钥不会提交。

> 控制台里的"**防火墙模板**"是给手工管理用的可复用规则集，脚本不走模板——直接对实例防火墙幂等追加规则，效果相同，无需维护模板。

**方式 B：控制台手工放行**

实例详情 → **安全组** tab → 编辑规则 → **添加入站规则**：

| 类型 | 端口 | 来源 | 用途 |
|------|------|------|------|
| TCP | **22** | 0.0.0.0/0 | SSH 登录（不放行后面全走不通） |
| TCP | **80** | 0.0.0.0/0 | 前端页面 + API 入口 |

> 只需这两个端口。后端 8080 在容器内被 nginx 反代、不对宿主机发布；5432/6379/9000/9001 只绑宿主机回环。需要直连调试走 SSH 隧道。

> 阿里云同理：ECS 控制台 → 安全组 → 配置规则 → 入方向添加。

**3. 首次 SSH 登录验证**（本地终端）

```bash
ssh ubuntu@<服务器IP>        # 输入控制台设置的密码，能进去就 OK
exit
```

> 报 `Permission denied (publickey)`：镜像默认禁了密码登录。两种处理：
> ① 控制台「重置密码」后重试；② 控制台 VNC 登录后执行
> `sudo sed -i 's/^#\?PasswordAuthentication.*/PasswordAuthentication yes/' /etc/ssh/sshd_config && sudo systemctl restart ssh`

## 第 ② 步：本地配置 SSH 免密

```bash
ssh-copy-id ubuntu@<服务器IP>   # 输一次密码；之后 ssh ubuntu@<IP> 不再要密码
ssh ubuntu@<服务器IP> "echo 免密OK"   # 验证
```

> Mac 没有 ssh-copy-id：`brew install ssh-copy-id` 或
> `cat ~/.ssh/id_rsa.pub | ssh ubuntu@<IP> 'mkdir -p ~/.ssh && cat >> ~/.ssh/authorized_keys'`

## 第 ③ 步：初始化服务器（本目录脚本，一条命令）

```bash
cd panjia-server/deploy/ubuntu

# 服务器信息自动读 ../docker/server.env（SERVER_IP / SERVER_USER）
bash setup-server.sh

# 或临时指定其它服务器
bash setup-server.sh <服务器IP> [ssh用户]
```

脚本自动完成（**全部幂等，重复执行安全**）：

| 步骤 | 内容 |
|------|------|
| 0. 云安全组 | 配了 API 密钥时：按公网 IP 自动找到安全组/实例防火墙，幂等放行 22/80；没配则跳过并提示 |
| 1. 系统基础 | apt 更新、curl/rsync/ufw 等工具、时区 Asia/Shanghai |
| 2. 防火墙 ufw | **先放行 22 再 enable**（防 SSH 锁死），只放行 22/80 |
| 3. Docker CE | 腾讯云内网 apt 源优先（自动探测，不通回退官方源），含 compose 插件 |
| 4. 加速源 | daemon.json：腾讯云内网 registry 源优先 + 日志限制 100m×5 |

跑完看到 `服务器初始化完成！` 即成功（中途失败直接重跑，自动续）。

## 第 ④ 步：部署系统 → [deploy/docker](../docker/README.md)

```bash
cd ../docker

# 首次部署（带授权码，一步到位自动激活 License）
PANJIA_AUTH_CODE=PJ-XXXX-XXXX-XXXX bash deploy.sh <服务器IP>
```

> - 授权码在授权管理后台按客户生成；也可部署后再补：写入服务器 `.env` 的 `PANJIA_AUTH_CODE=` 后 `ssh <IP> 'cd ~/panjia && sudo ./restart.sh'`
> - ⚠️ **授权码绑定机器指纹**：服务器重装系统/换机器后原码可能失效，需回授权管理后台重新生成
> - 日常发版：`bash update-backend.sh` / `bash update-frontend.sh`（不用再碰授权码）

## 第 ⑤ 步：验证上线

```bash
ssh ubuntu@<服务器IP> 'cd ~/panjia && sudo docker ps --format "{{.Names}}  {{.Status}}"'
# 5 个容器全部 (healthy)，然后浏览器访问 http://<服务器IP> 能看到登录页
```

---

## 常见问题

| 现象 | 处理 |
|------|------|
| SSH 连不上（超时） | 云安全组没放 22；或服务器没开机/重置还没完成 |
| `Permission denied (publickey)` | 见第 ① 步末尾的密码登录处理 |
| 免密配了还是要密码 | `ssh-copy-id` 没成功或登录用户不对；确认 `ssh ubuntu@IP`（不是 root） |
| `ufw enable` 后 SSH 断了 | 不会发生：脚本先 allow 22 再 enable；若断了就是安全组没放 22 |
| Docker 拉镜像超时 | 看初始化步骤 4 输出的 Registry Mirrors；腾讯云 CVM 内网源必通 |
| `sh setup-server.sh` 报错 | 用 `bash` 执行，脚本用了 bash 特性 |
| 后端容器反复重启、日志见 `未检测到有效 token` | 没配授权码，见第 ④ 步 |
| 后端启动日志见 `machine-id 未挂载` | compose 已内置宿主机 `/etc/machine-id` 只读挂载，确认用的是最新 docker-compose.yml |
