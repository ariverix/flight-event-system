# ADR-0006: Rate limiting на процессе приложения (bucket4j, in-memory token bucket)

**Status:** Accepted

**Date:** 2026-07-03

**Authors:** security-agent (Фаза 3 прогона «Промышленный апгрейд»), оркестратор

## Context

Чек-лист ИБ (threat model R-4, fstec-checklist ИАФ) требовал защиту от брутфорса
`/auth/login` и флуда открытого ACARS-ингеста `/api/v1/messages/**` (permitAll по
дизайну — внешняя машина без JWT, сетевое ограждение mTLS/allowlist, CLAUDE.md §7).
До Фазы 3 rate limiting отсутствовал вовсе — единственный незакрытый пункт чек-листа.

Ограничения: импортозамещение (зависимость должна быть совместима с Реестром
российского ПО), HA-топология из P6-1 (2+ реплики backend), пиковый штатный поток
ингеста ~375 msg/s (P6-3), отсутствие Redis/внешнего стора в стеке (осознанно —
PostgreSQL-only инфраструктура, ADR-0004).

## Decision

Мы делаем rate limiting **внутри процесса приложения** на библиотеке
`com.bucket4j:bucket4j_jdk17-core` (Apache-2.0, 0 транзитивных зависимостей):

- `RateLimitFilter` в security-цепочке ПЕРЕД `JwtAuthenticationFilter` (после CORS);
  token bucket **per client IP**, два независимых scope:
  - `AUTH` — `/api/v1/auth/login|refresh|logout` (дефолт 10/60с) — анти-брутфорс;
  - `MESSAGES` — `/api/v1/messages/**` (дефолт 2000/с, выше пика ~375 msg/s) —
    потолок от флуда, штатный поток не режется.
- Отказ — HTTP 429 RFC 7807 Problem Details + `Retry-After`; метрика
  `eca.ratelimit.rejected{scope}`; алерты `EcaAuthRateLimitSpike`/`EcaMessagesRateLimitSpike`.
- `X-Forwarded-For` учитывается ТОЛЬКО от доверенных прокси
  (`app.ratelimit.trusted-proxies`, IP/CIDR; дефолт пуст → ключ = `remoteAddr`) —
  анти-спуфинг; карта бакетов — ограниченный LRU (`maxTrackedKeys`, дефолт 100k) —
  анти-memory-DoS.
- Пороги/тумблер — `app.ratelimit.*` (env); в тестах surefire выключает
  (`app.ratelimit.enabled=false`), в проде включён по умолчанию.

Границы модулей не затронуты: фильтр живёт в корневом пакете (инфраструктурный
срез, как `RetentionService`), модульных портов не добавлено.

## Consequences

**Положительные:**
- Закрыт последний пункт чек-листа ИБ; R-4 в threat model переведён в Mitigated.
- Zero-infrastructure: нет нового стейт-стора, деплой не усложнён.
- Лимит у каждой реплики свой → отказ одной реплики не снимает защиту с остальных.

**Отрицательные / принятые риски:**
- Лимит **per-replica, не кластерный**: при N репликах злоумышленник, размазанный
  балансировщиком, получает до N× порога. Для анти-брутфорса это приемлемо
  (10×2=20/мин всё ещё жёстко); при росте реплик пороги пересмотреть.
- In-memory состояние теряется при рестарте реплики (окно 60с — незначимо).
- Правильность ключа зависит от корректного `trusted-proxies` в топологии с LB.

**Что это требует от команды дальше:**
- При переходе на >2 реплик или требовании строго кластерного лимита — вынести
  состояние в PostgreSQL (по образцу lease-election ADR-0004) или пересчитать пороги.
- Держать `app.ratelimit.trusted-proxies` синхронным с топологией ingress.

## Alternatives considered

| Альтернатива | Почему не выбрана |
|---|---|
| Redis-backed (bucket4j-redis, resilience4j+Redis) | Новый инфраструктурный компонент ради одного лимитера; проект осознанно PostgreSQL-only (ADR-0004, импортозамещение) |
| Rate limiting на ingress/reverse-proxy (nginx limit_req) | Дополняет, но не заменяет: приложение обязано защищаться само (deployment-agnostic, dev/compose без ingress); путь для ГОСТ TLS-прокси остаётся открытым (docs/security/gost-tls-path.md) |
| Spring Cloud Gateway RequestRateLimiter | Тянет Spring Cloud + Redis; несоразмерно модульному монолиту |
| Самописный счётчик на ConcurrentHashMap | Token bucket с refill правильнее интервального счётчика (нет granularity-burst на границе окна); bucket4j — 0 транзитивов, Apache-2.0, проверенная реализация |

## References

- Код: `backend/src/main/java/ru/protectinfotrans/eca/RateLimitFilter.java`, тест `RateLimitFilterTest` (9)
- Конфиг: `application.yml` `app.ratelimit.*`; алерты `observability/prometheus/alerts.yml` (группа eca.security)
- docs/security/threat-model.md (R-4), docs/security/fstec-checklist.md
- docs/PROGRESS.md — Фаза 3 прогона 2026-07-03 (вкл. HIGH-фикс ревью: XFF-спуфинг, LRU)
