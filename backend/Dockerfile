# Build stage
FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /app
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw dependency:go-offline -B
COPY src/ src/
RUN ./mvnw package -DskipTests -B

# Runtime stage
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
# Запуск не от root (defense-in-depth; semgrep dockerfile missing-user-entrypoint).
# Порт 8080 > 1024 — привилегии root не нужны.
RUN addgroup -S app && adduser -S -G app app && chown -R app:app /app
USER app
EXPOSE 8080
# -Xss8m: ExecutionService.advanceExecution/executeTransition — прямая взаимная рекурсия
# (внутри уже открытой @Transactional-транзакции, без переисполнения прокси на итерации) до
# MAX_SYNCHRONOUS_TRANSITIONS=1000. Alpine (musl libc) даёт заметно меньший дефолтный размер
# стека потока, чем glibc — тот же класс StackOverflowError, что воспроизводимо ловился в
# тестах на GitHub Actions runners (2026-08-28, см. комментарий в backend/pom.xml
# surefire-конфиге).
ENTRYPOINT ["java", "-Xss8m", "-jar", "app.jar"]
