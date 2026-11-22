FROM eclipse-temurin:21-jre-jammy

# 安装 PostgreSQL 16 客户端（备份恢复功能 local 模式用，与 Dockerfile.local 同口径）：
# 生产容器内无 docker CLI，BackupServiceImpl 直接执行 pg_dump/pg_restore 经 TCP
# 连 postgres 容器（compose 里 PANJIA_BACKUP_MODE=local，见 application.yml 的 panjia.backup）。
# jammy 默认源只提供 client-14，旧客户端不能 dump 16.x 服务端，必须挂 pgdg 源装 16。
# 加速策略：ubuntu 主源（包索引几十 MB，慢的大头）换清华镜像；
# pgdg 保持官方源——postgresql-client-16 仅几 MB，官方源够用，
# 且国内镜像对 pgdg 的镜像在容器内 apt 取 Release 时易 404（实测），不可靠。
RUN sed -i 's|http://archive.ubuntu.com|https://mirrors.tuna.tsinghua.edu.cn|g; \
            s|http://security.ubuntu.com|https://mirrors.tuna.tsinghua.edu.cn|g' \
        /etc/apt/sources.list \
    && apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates gnupg lsb-release wget \
    && install -d /usr/share/postgresql-common/pgdg \
    && wget -q -O /usr/share/postgresql-common/pgdg/apt.postgresql.org.asc \
        https://www.postgresql.org/media/keys/ACCC4CF8.asc \
    && echo "deb [signed-by=/usr/share/postgresql-common/pgdg/apt.postgresql.org.asc] \
http://apt.postgresql.org/pub/repos/apt $(lsb_release -cs)-pgdg main" \
        > /etc/apt/sources.list.d/pgdg.list \
    && apt-get update \
    && apt-get install -y --no-install-recommends postgresql-client-16 \
    && rm -rf /var/lib/apt/lists/*

RUN mkdir -p /ruoyi/server/logs /ruoyi/server/temp /ruoyi/server/data /ruoyi/server/backup

WORKDIR /ruoyi/server

COPY ruoyi-admin/target/ruoyi-admin.jar /ruoyi/server/app.jar

ENV SERVER_PORT=8080 LANG=C.UTF-8 LC_ALL=C.UTF-8 JAVA_OPTS="-Xms512m -Xmx1024m -XX:+UseG1GC"

EXPOSE 8080

ENTRYPOINT ["sh", "-c", "java -Djava.security.egd=file:/dev/./urandom -Dserver.port=${SERVER_PORT} -XX:+HeapDumpOnOutOfMemoryError ${JAVA_OPTS} -jar /ruoyi/server/app.jar"]
