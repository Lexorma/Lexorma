# MetaMora 이미지 (docker) : React + Spring Boot 를 하나의 jar 로 빌드해 실행한다.
#   docker build -f Dockerfile -t koboolean/metamora:latest .   (빌드 컨텍스트는 저장소 루트)
# 1) Node : React 빌드 (metamora-web/dist)  2) JDK : dist 를 넣어 metamora.jar 생성  3) JRE : 실행 (Node 없음)

# =========================================
# Stage 1. React Build
# =========================================
FROM node:24.11.1-alpine3.22 AS frontend-build

WORKDIR /workspace/metamora-web

COPY metamora-web/package.json metamora-web/package-lock.json ./
RUN npm ci --no-audit --no-fund

COPY metamora-web/ ./
RUN npm run build

# =========================================
# Stage 2. Spring Boot Build
# =========================================
FROM eclipse-temurin:21-jdk AS backend-build

WORKDIR /workspace

COPY . .
# 로컬의 오래된 dist 가 아니라 Stage 1 에서 새로 만든 결과를 쓴다 (.dockerignore 로 로컬 dist 는 제외)
COPY --from=frontend-build /workspace/metamora-web/dist/ /workspace/metamora-web/dist/

RUN chmod +x ./gradlew \
    && ./gradlew :metamora-app:bootJar --no-daemon -PfrontendPrebuilt=true

# =========================================
# Stage 3. Runtime
# =========================================
FROM eclipse-temurin:21-jre

WORKDIR /app

COPY --from=backend-build /workspace/metamora-app/build/libs/metamora.jar /app/metamora.jar

EXPOSE 9940

ENTRYPOINT ["java", "-jar", "/app/metamora.jar"]
CMD ["--spring.profiles.active=docker"]
