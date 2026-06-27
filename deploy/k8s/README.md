# Kubernetes — развёртывание eca-backend

## Файлы

| Файл | Что делает |
|---|---|
| `backend-deployment.yaml` | Deployment + Service: 2 реплики, liveness/readiness/startup пробы (P5-3), graceful shutdown, preStop-пауза (P6-1) |
| `backend-hpa.yaml` | HorizontalPodAutoscaler: CPU-целевое 70%, min 2 / max 8 реплик (P6-2) |

## Горизонтальное масштабирование (HPA)

HPA (`backend-hpa.yaml`) масштабирует Deployment `eca-backend` по средней загрузке CPU:

- **resources.requests.cpu = 500m** (backend-deployment.yaml) — база для расчёта HPA.
- **target: 70% от requests** → при среднем CPU > 350m на pod добавляется реплика.
- **min/max: 2–8 реплик** — HA-минимум 2 (rolling-update без downtime) и практический потолок первого деплоя.
- **Scale-up**: +2 пода / 60 с (быстрая реакция на трафиковые всплески ACARS).
- **Scale-down**: -1 под / 120 с, окно стабилизации 5 мин (избегает «пилы»).

## Почему leader election гарантирует single-fire при N репликах

Три класса @Scheduled-поллеров гейтируются через `LeaderElection.isLeader()`:

| Планировщик | Таблица | Механизм |
|---|---|---|
| `WaitTimeoutScheduler` | execution_instances | DB-claim (UPDATE ... WHERE wait_timeout_at = expected) |
| `OutboundMessageDeliveryScheduler` | outbound_messages | DB-claim (UPDATE ... WHERE status = PENDING) |
| `RetentionService` | tracking_event_log, messages, audit_log | DROP/DELETE — идемпотентно |

**Схема**: только реплика-лидер (держит актуальный lease в `leader_election`, V36) вызывает
эти методы. DB-claim в первых двух планировщиках — defense-in-depth: даже при кратковременном
«раздвоении» лидерства (нормальная ситуация при смене лидера, см. ADR-0004) двойного
срабатывания таймаута/доставки не будет. `RetentionService` идемпотентен сам по себе:
`IF NOT EXISTS` / `DROP TABLE IF EXISTS` / `DELETE WHERE` безопасно выполняются несколькими
репликами без потери данных.

**При добавлении реплик**: k8s читает `/actuator/health/readiness` перед включением pod'а в
ротацию. Пока Flyway ещё мигрирует (startup probe не прошла), pod не получает трафик.
После `ApplicationReadyEvent` новая реплика пытается захватить lease leader election; если
lease уже занят — она работает как follower (без @Scheduled-тиков), но принимает HTTP-запросы.
