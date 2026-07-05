# ===== STAGE 1: Build =====
FROM eclipse-temurin:21-jdk-alpine AS builder

WORKDIR /app

# Cache de dependências Gradle
COPY build.gradle.kts settings.gradle.kts gradle.properties ./
COPY gradle/ gradle/
COPY gradlew ./
RUN chmod +x gradlew && ./gradlew dependencies --no-daemon

# Compilação do projeto
COPY src/ src/
RUN ./gradlew shadowJar --no-daemon

# ===== STAGE 2: Runtime =====
FROM eclipse-temurin:21-jre-alpine AS runtime

RUN addgroup -S estap && adduser -S estap -G estap

WORKDIR /app

COPY --from=builder /app/build/libs/*-all.jar estap.jar

RUN chown -R estap:estap /app
USER estap

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=3s --retries=3 \
  CMD wget --no-verbose --tries=1 --spider http://localhost:8080/estap/health || exit 1

ENTRYPOINT ["java", "-XX:+UseZGC", "-XX:MaxRAMPercentage=75.0", "-jar", "estap.jar"]
