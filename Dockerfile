# =============================================================================
# Week 3 Day 1：多阶段构建
# 阶段 1（build）：用 Maven + JDK 21 在容器内执行 mvn package，产出可执行 fat jar
# 阶段 2（runtime）：只带 JRE 21 + jar，镜像更小、攻击面更小（无编译器、无源码）
#
# 【拉取基础镜像失败 / 连不上 Docker Hub】
# 在国内常见。任选其一：
#
# 方案 A（推荐）：Docker Desktop → Settings → Docker Engine，在 JSON 里增加
#   "registry-mirrors": [ "https://<你的加速器地址>" ]
# 阿里云/腾讯云控制台「容器镜像服务」里可拿到个人加速器地址，保存后重启 Docker。
#
# 方案 B：默认已使用 DaoCloud 对 Docker Hub 的代理路径（见下方 ARG 默认值）。
#   若你在海外网络或代理失效，可改回官方 Hub：
#   docker build -t finance-agent:local `
#     --build-arg MAVEN_IMAGE=maven:3.9-eclipse-temurin-21 `
#     --build-arg JRE_IMAGE=eclipse-temurin:21-jre-jammy .
# =============================================================================

# 两个 ARG 必须放在「第一个 FROM 之前」，第二阶段的 FROM ${JRE_IMAGE} 才能解析到默认值
# 默认走 docker.m.daocloud.io，避免直连 docker.io（国内常超时）；海外可 build-arg 覆盖为官方名
ARG MAVEN_IMAGE=docker.m.daocloud.io/library/maven:3.9-eclipse-temurin-21
ARG JRE_IMAGE=docker.m.daocloud.io/library/eclipse-temurin:21-jre-jammy

FROM ${MAVEN_IMAGE} AS build
WORKDIR /app

COPY pom.xml .
COPY src ./src

RUN mvn -B -DskipTests package

FROM ${JRE_IMAGE}
WORKDIR /app

RUN groupadd --system spring && useradd --system --gid spring --no-create-home spring

COPY --from=build /app/target/finance-agent-1.0-SNAPSHOT.jar app.jar
RUN chown spring:spring app.jar

USER spring:spring

EXPOSE 8081

ENV JAVA_TOOL_OPTIONS=""

ENTRYPOINT ["sh", "-c", "exec java $JAVA_TOOL_OPTIONS -jar /app/app.jar"]

# -----------------------------------------------------------------------------
# 验收（Day1）
#   docker build -t finance-agent:local .
#   若仍报 auth.docker.io / 超时，使用文件头「方案 A」或「方案 B」
# -----------------------------------------------------------------------------
# 运行示例（需本机或 Compose 提供 Postgres/Redis；仅示例）：
#   docker run --rm -p 8081:8081 -e SPRING_DATASOURCE_URL=... -e APP_JWT_SECRET=... finance-agent:local
# -----------------------------------------------------------------------------
