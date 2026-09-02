# Architecture Decision Records

ADR фиксируют кросс-модульные и стратегические технические решения проекта
`flight-event-system`. Новые ADR — по шаблону `ADR-template.md`, нумерация
сквозная, статус не меняется задним числом — добавляется новый ADR со
статусом `Superseded by ADR-XXXX` у старого при пересмотре решения.

Когда заводить ADR — см. роль `architect` в `CLAUDE.md` / `.claude/agents/`:
новая подсистема, изменение, затрагивающее >1 модуль или публичный контракт
между модулями, спор вида «монолит vs микросервисы», «event bus vs Outbox».

## Индекс

| № | Название | Статус |
|---|---|---|
| [ADR-0001](ADR-0001-modular-monolith-vs-microservices.md) | Модульный монолит (Spring Modulith) против микросервисов | Accepted |
| [ADR-0002](ADR-0002-transactional-outbox-vs-direct-call.md) | Transactional Outbox (Spring Modulith Event Publication Registry) против прямого синхронного вызова между модулями | Accepted |
| [ADR-0003](ADR-0003-jwt-access-refresh-and-token-crypto.md) | Короткоживущий access JWT + opaque refresh с ротацией; BCrypt/SHA-256 | Accepted |
| [ADR-0004](ADR-0004-leader-election.md) | Lease-based leader election на PostgreSQL (без ShedLock/ZooKeeper) | Accepted |
| [ADR-0005](ADR-0005-frontend-architecture.md) | Архитектура фронтенда: слои, Zustand, OpenAPI-клиент, WS-слой, i18n | Accepted |
| [ADR-0006](ADR-0006-rate-limiting.md) | Rate limiting на процессе приложения (bucket4j, in-memory token bucket) | Accepted |
| [ADR-0007](ADR-0007-start-dedup-db-unique-claim.md) | Дедупликация startExecution через частичный UNIQUE-индекс БД (V38) | Accepted |
| [ADR-0008](ADR-0008-spring-boot-4-migration.md) | Переход на Spring Boot 4.x / Spring Framework 7.x / Spring Security 7.x | Proposed |
