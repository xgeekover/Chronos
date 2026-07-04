# Chronos backend runtime image (§11 배포). Build the boot jar on the host first:
#   ./gradlew :app:bootJar          → backend/app/build/libs/chronos-app.jar
# then `docker compose build`. (Keeping the jar build outside the image makes `compose up` fast;
# CI builds the jar and this image just packages it.)
FROM eclipse-temurin:17-jre

RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY app/build/libs/chronos-app.jar app.jar

EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=3s --start-period=40s --retries=5 \
    CMD curl -fsS http://localhost:8080/actuator/health || exit 1

# Graceful shutdown is enabled in application.yml (server.shutdown=graceful).
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
