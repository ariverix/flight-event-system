# Отчёт о лицензиях зависимостей — ECA System

**Версия:** 1.1  
**Дата:** 2026-07-04 (v1.0 — 2026-06-28, P8-3; v1.1 — прогон «Промышленный апгрейд»: +bucket4j, +@playwright/test)  
**Статус:** Актуально  
**Ответственный:** compliance-agent  
**Источники:** `backend/pom.xml`, `frontend/package.json`, анализ ветки `main`  

Отчёт охватывает все production-зависимости (runtime + compile scope); test-only зависимости вынесены в отдельный раздел с пометкой «тесты». Инфраструктурные компоненты рассматриваются отдельно.

**Вывод:** ни одна production-зависимость не имеет лицензии, запрещающей коммерческое использование или включение в Реестр российского ПО. Единственная чувствительная позиция — Hibernate ORM (LGPL 2.1) — используется исключительно как динамически подключаемая библиотека через JPA-контракт; исходный код Hibernate не модифицируется; LGPL-ограничение на распространение производных не распространяется на данный способ использования. Рекомендуется юридическая верификация этой позиции специалистом по лицензиям заказчика.

---

## 1. Backend зависимости (Java/Maven)

### 1.1 Runtime и compile scope

| Артефакт | Версия | Лицензия | Примечание |
|---|---|---|---|
| spring-boot-starter-web | 3.5.15 | Apache License 2.0 | Транзитивно тянет spring-webmvc, spring-core, Tomcat embedded |
| spring-boot-starter-websocket | 3.5.15 | Apache License 2.0 | Raw WebSocket /ws/eca |
| spring-boot-starter-data-jpa | 3.5.15 | Apache License 2.0 | Spring Data JPA |
| spring-boot-starter-security | 3.5.15 | Apache License 2.0 | Spring Security |
| spring-boot-starter-validation | 3.5.15 | Apache License 2.0 | Bean Validation / Hibernate Validator |
| spring-boot-starter-actuator | 3.5.15 | Apache License 2.0 | Health/metrics endpoints |
| spring-modulith-starter-core | 1.3.1 | Apache License 2.0 | Модульная структура |
| spring-modulith-starter-jpa | 1.3.1 | Apache License 2.0 | JPA-интеграция Spring Modulith |
| easy-rules-core | 4.1.0 | MIT License | Движок правил ECA (Java) |
| easy-rules-support | 4.1.0 | MIT License | Вспомогательные классы Easy Rules |
| postgresql (JDBC driver) | 42.x (управляется Spring Boot BOM) | BSD 2-Clause | JDBC-драйвер PostgreSQL |
| flyway-core | 10.x (Spring Boot BOM) | Apache License 2.0 | Миграции схемы БД |
| flyway-database-postgresql | 10.x (Spring Boot BOM) | Apache License 2.0 | PostgreSQL-расширение Flyway |
| jjwt-api | 0.12.6 | Apache License 2.0 | JWT API |
| jjwt-impl | 0.12.6 | Apache License 2.0 | JWT реализация (runtime) |
| jjwt-jackson | 0.12.6 | Apache License 2.0 | JWT Jackson сериализатор (runtime) |
| springdoc-openapi-starter-webmvc-ui | 2.8.4 | Apache License 2.0 | OpenAPI/Swagger UI |
| lombok | 1.18.x (Spring Boot BOM) | MIT License | Кодогенерация (annotation processor, не входит в артефакт) |
| micrometer-registry-prometheus | управляется Spring Boot BOM | Apache License 2.0 | Экспорт метрик Prometheus |
| micrometer-tracing-bridge-otel | управляется Spring Boot BOM | Apache License 2.0 | Мост Micrometer → OpenTelemetry |
| opentelemetry-exporter-otlp | управляется Spring Boot BOM | Apache License 2.0 | OTLP-экспорт трейсов |
| jackson-databind | 2.18.x (транзитивно, Spring Boot BOM) | Apache License 2.0 | JSON-сериализация |
| HikariCP | 5.x (транзитивно, Spring Boot BOM) | Apache License 2.0 | Пул соединений JDBC |
| Hibernate ORM | 6.6.x (транзитивно, Spring Boot BOM) | GNU LGPL v2.1 | JPA-провайдер. РИСК: см. примечание ниже |
| Hibernate Validator | 8.x (транзитивно) | Apache License 2.0 | Bean Validation реализация |
| Apache Tomcat Embedded | 10.1.x (транзитивно) | Apache License 2.0 | Встроенный веб-сервер |
| SLF4J + Logback | управляется Spring Boot BOM | MIT / EPL 1.0 | Логирование. EPL 1.0 — не вирусная лицензия для использования как библиотеки |
| context-propagation (Micrometer) | управляется Spring Boot BOM | Apache License 2.0 | Проброс контекста через @Async |
| bucket4j (bucket4j_jdk17-core) | 8.19.0 | Apache License 2.0 | Rate limiting (token bucket, ADR-0006). Добавлен прогоном 2026-07-03 (Фаза 3); координаты/лицензия сверены с Maven Central, 0 транзитивных зависимостей |

**Примечание по Hibernate ORM (LGPL 2.1):**  
LGPL 2.1 разрешает использование библиотеки в проприетарных/коммерческих продуктах при условии, что: (а) Hibernate используется как динамически подключаемая библиотека (not statically linked), (б) исходный код Hibernate не модифицируется, (в) при распространении предоставляется доступ к используемой версии Hibernate (стандартный Maven Central). Все три условия выполняются в ECA System. Тем не менее рекомендуется верификация с юристом заказчика применительно к конкретным условиям распространения в рамках ФГУП.

### 1.2 Тестовые зависимости (test scope — не входят в производственный артефакт)

| Артефакт | Версия | Лицензия | Примечание |
|---|---|---|---|
| spring-boot-starter-test | 3.5.15 | Apache License 2.0 | JUnit 5, Mockito, AssertJ |
| spring-modulith-starter-test | 1.3.1 | Apache License 2.0 | `ApplicationModules.verify()` |
| spring-security-test | управляется Boot BOM | Apache License 2.0 | Security-тесты |
| testcontainers:postgresql | 1.20.4 | MIT License | PostgreSQL в тестах |
| testcontainers:junit-jupiter | 1.20.4 | MIT License | JUnit 5 интеграция Testcontainers |
| h2 | 2.x (Spring Boot BOM) | MPL 2.0 / EPL 1.0 | In-memory БД для части unit-тестов. Только test scope. |
| opentelemetry-sdk-testing | управляется Boot BOM | Apache License 2.0 | InMemorySpanExporter для тестов трейсинга |

### 1.3 Сборочные плагины (Maven plugins — не входят в артефакт поставки)

| Плагин | Версия | Лицензия |
|---|---|---|
| spring-boot-maven-plugin | 3.5.15 | Apache License 2.0 |
| maven-surefire-plugin | управляется Boot BOM | Apache License 2.0 |
| jacoco-maven-plugin | 0.8.15 | EPL 2.0 — только для сборки/CI |
| license-maven-plugin | 2.4.0 | Apache License 2.0 |
| dependency-check-maven (профиль security-scan) | 10.0.4 | Apache License 2.0 |
| spotbugs-maven-plugin (профиль security-scan) | 4.8.6.4 | LGPL 2.1 — только для статического анализа в CI |
| findsecbugs-plugin (профиль security-scan) | 1.13.0 | LGPL 2.1 — только для CI |

---

## 2. Frontend зависимости (npm)

### 2.1 Runtime зависимости (dependencies)

| Пакет | Версия | Лицензия | Примечание |
|---|---|---|---|
| react | ^18.3.1 | MIT License | UI-фреймворк |
| react-dom | ^18.3.1 | MIT License | DOM-рендеринг React |
| antd | ^5.23.4 | MIT License | UI-компонентная библиотека Ant Design |
| @ant-design/icons | ^5.5.2 | MIT License | Иконки Ant Design |
| @xyflow/react | ^12.4.1 | MIT License | Граф-редактор (React Flow 12) для визуализации последовательностей |
| axios | ^1.7.9 | MIT License | HTTP-клиент |
| dagre | ^0.8.5 | MIT License | Автолейаут графа (направленный ациклический граф) |
| react-router-dom | ^7.1.1 | MIT License | Маршрутизация SPA |
| zustand | ^5.0.14 | MIT License | Управление состоянием |

### 2.2 Разработческие зависимости (devDependencies — не входят в производственный бандл)

| Пакет | Версия | Лицензия |
|---|---|---|
| vite | ^6.0.6 | MIT License |
| @vitejs/plugin-react | ^4.3.4 | MIT License |
| typescript | ~5.6.2 | Apache License 2.0 |
| vitest | ^4.1.9 | MIT License |
| @testing-library/react | ^16.3.2 | MIT License |
| @testing-library/user-event | ^14.6.1 | MIT License |
| eslint | ^9.17.0 | MIT License |
| eslint-plugin-react-hooks | ^5.1.0 | MIT License |
| eslint-plugin-react-refresh | ^0.4.16 | MIT License |
| typescript-eslint | ^8.18.2 | MIT License |
| openapi-typescript | ^7.13.0 | MIT License |
| jsdom | ^29.1.1 | MIT License |
| globals | ^15.14.0 | MIT License |
| @types/* | различные | MIT License |
| @playwright/test | ^1.61.1 | Apache License 2.0 |

**Примечание по Playwright (добавлен прогоном 2026-07-03, Фаза 6):** сам пакет — Apache 2.0;
браузерные бинарники (Chromium — BSD-подобные лицензии) скачиваются `npx playwright install`
только на дев-машине/CI-раннере для E2E-тестов и НЕ входят ни в production-бандл, ни в
docker-образы поставки.

---

## 3. Инфраструктурные компоненты

| Компонент | Версия | Лицензия | Примечание |
|---|---|---|---|
| PostgreSQL | 16 | PostgreSQL License (BSD-подобная) | Открытая лицензия, коммерческое использование разрешено без ограничений |
| OpenJDK | 21 | GPL v2 + Classpath Exception | Classpath Exception явно разрешает использование JVM-рантайма для запуска проприетарных программ без GPL-инфицирования. Стандартное основание для использования в коммерческом ПО. |
| Node.js | 20 LTS (сборка фронтенда) | MIT License | Только для сборки, не входит в production-образ |
| Docker Engine | 24+ | Apache License 2.0 | Контейнеризация |
| nginx / Angie | по выбору заказчика | BSD 2-Clause (nginx) / Apache 2.0 (Angie) | Reverse-proxy для ГОСТ TLS-терминации (планируется P8-1). Angie — российский форк nginx (ПАО «Вебпрактика», Реестр российского ПО), предпочтителен с точки зрения импортозамещения. |
| Kubernetes / Helm | по выбору заказчика | Apache License 2.0 | Оркестрация (планируется P8-1) |
| Prometheus | по выбору заказчика | Apache License 2.0 | Сбор метрик |
| OpenTelemetry Collector | по выбору заказчика | Apache License 2.0 | OTLP-получатель трейсов |

---

## 4. Итоговая оценка лицензионной совместимости

| Лицензия | Количество позиций (production) | Оценка для Реестра российского ПО |
|---|---|---|
| Apache License 2.0 | ~25 | Совместима. Коммерческое использование, распространение и модификация разрешены. |
| MIT License | ~10 | Совместима. Наиболее разрешительная лицензия. |
| BSD 2-Clause / PostgreSQL License | 2 | Совместима. BSD-подобные лицензии разрешают коммерческое использование. |
| GNU LGPL v2.1 | 1 (Hibernate ORM, динамически) | Совместима при соблюдении условий LGPL (динамическое связывание, без модификации). Требует юридической верификации у заказчика. |
| EPL 1.0 (Logback) | 1 (транзитивно) | Eclipse Public License — не вирусная для использования как библиотеки. Коммерческое использование разрешено. |

**Заключение:** ни одна production-зависимость не содержит «сильной» копилефт-лицензии (GPL без исключений, AGPL и т.п.), которая запрещала бы включение в коммерческое распространяемое ПО. Все лицензии совместимы между собой и с требованиями Реестра российского ПО по лицензионной чистоте.

---

## 5. Автоматизированный license report в CI

В `backend/pom.xml` настроен плагин `license-maven-plugin` (goal `aggregate-third-party-report`, фаза `verify`). При запуске `mvn verify` генерируется HTML-отчёт `target/site/aggregate-third-party-report.html` со списком всех зависимостей, их координат и лицензий (включая транзитивные). Отчёт публикуется как CI-артефакт при каждой сборке GitHub Actions.

Команда для локальной генерации:
```
mvn -B verify -DskipTests
# отчёт: backend/target/site/aggregate-third-party-report.html
```

OWASP Dependency-Check (профиль `security-scan`) запускается отдельным CI-job: `mvn -Psecurity-scan dependency-check:check`. Фейл при CVSS >= 7 (High/Critical CVE). NVD API-ключ подаётся через секрет `NVD_API_KEY`.

---

*Документ подлежит обновлению при добавлении новых зависимостей. Любая новая зависимость проходит проверку лицензии compliance-agent до принятия (правило проекта, CLAUDE.md).*
