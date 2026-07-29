# ============================
# Stage 1: Build
# ============================
FROM eclipse-temurin:21-jdk AS builder

WORKDIR /app

# Copy Gradle wrapper & config trước để tận dụng Docker layer cache
COPY gradle/wrapper/ gradle/wrapper/
COPY gradlew .
COPY build.gradle settings.gradle ./

# Tải dependencies trước (cache layer này nếu build.gradle không đổi)
RUN chmod +x gradlew && ./gradlew dependencies --no-daemon

# Copy toàn bộ source code
COPY src/ src/

# Build JAR, bỏ qua test để tăng tốc
RUN ./gradlew bootJar --no-daemon -x test

# ============================
# Stage 2: Runtime
# ============================
# FROM eclipse-temurin:25-jre AS runtime
FROM ibm-semeru-runtimes:open-21-jre AS runtime

WORKDIR /app

# Tạo user non-root để tăng bảo mật
RUN groupadd --system appgroup && useradd --system --gid appgroup appuser

# Copy JAR từ stage build
COPY --from=builder /app/build/libs/*.jar app.jar

# Đổi quyền sở hữu file JAR
RUN chown appuser:appgroup app.jar

USER appuser

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
