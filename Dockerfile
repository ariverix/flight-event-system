# ============================================================
# Stage 1: Build frontend (React + Vite)
# ============================================================
FROM node:20-alpine AS frontend-build
WORKDIR /frontend
COPY frontend/package*.json ./
RUN npm ci
COPY frontend/ ./
RUN npm run build

# ============================================================
# Stage 2: Build backend (Maven + Java 21)
# ============================================================
FROM eclipse-temurin:21-jdk-alpine AS backend-build
WORKDIR /app
COPY backend/.mvn/ .mvn/
COPY backend/mvnw backend/pom.xml ./
RUN sed -i 's/\r//' mvnw && chmod +x mvnw && ./mvnw dependency:go-offline -B
COPY backend/src/ src/
# Копировать собранный фронтенд в статические ресурсы Spring Boot
COPY --from=frontend-build /frontend/dist/ src/main/resources/static/
RUN ./mvnw package -DskipTests -B

# ============================================================
# Stage 3: Runtime (JRE only)
# ============================================================
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=backend-build /app/target/*.jar app.jar
# Запуск не от root (defense-in-depth; semgrep dockerfile missing-user-entrypoint).
# Порт 8080 > 1024 — привилегии root не нужны.
RUN addgroup -S app && adduser -S -G app app && chown -R app:app /app
USER app
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]