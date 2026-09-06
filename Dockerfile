FROM docker.m.daocloud.io/library/eclipse-temurin:21-jre-jammy

RUN mkdir -p /ruoyi/server/logs /ruoyi/server/temp /ruoyi/server/data

WORKDIR /ruoyi/server

COPY ruoyi-admin/target/ruoyi-admin.jar /ruoyi/server/app.jar

ENV SERVER_PORT=8080 LANG=C.UTF-8 LC_ALL=C.UTF-8 JAVA_OPTS="-Xms512m -Xmx1024m -XX:+UseG1GC"

EXPOSE 8080

ENTRYPOINT ["sh", "-c", "java -Djava.security.egd=file:/dev/./urandom -Dserver.port=${SERVER_PORT} -XX:+HeapDumpOnOutOfMemoryError ${JAVA_OPTS} -jar /ruoyi/server/app.jar"]
