# syntax=docker/dockerfile:1

# ---- build stage: compile and package with Maven ----
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
COPY src ./src
RUN mvn -B -ntp -q -DskipTests package

# ---- runtime stage: JRE only, non-root ----
FROM eclipse-temurin:24-jre-alpine
RUN addgroup -S -g 10001 ledger && adduser -S -u 10001 -G ledger ledger
WORKDIR /app
COPY --from=build /src/target/deployment-ledger.jar app.jar

USER 10001:10001
ENV PORT=8080
EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=3s --start-period=10s --retries=3 \
  CMD wget -qO- "http://127.0.0.1:${PORT}/health" >/dev/null || exit 1

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-XX:+ExitOnOutOfMemoryError", "-jar", "/app/app.jar"]
