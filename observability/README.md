# Observability — ECA System (P5-1 / P5-3)

Каталог содержит конфигурации наблюдаемости как код: Grafana-дашборды, Prometheus alert-правила и определения SLO. Все файлы версионируются в репозитории — изменения проходят ревью (CLAUDE.md §5).

## Импортозамещение

Стек наблюдаемости строится на самостоятельно развёртываемых компонентах с открытым исходным кодом — без зарубежных проприетарных SaaS (CLAUDE.md «Импортозамещение»):

| Компонент | Продукт | Лицензия |
|-----------|---------|----------|
| Сбор метрик | **Prometheus** | Apache 2.0 |
| Визуализация | **Grafana** | AGPL 3.0 |
| Трейсинг | **OpenTelemetry Collector** + **Jaeger** | Apache 2.0 |
| Health checks | Spring Boot Actuator | Apache 2.0 |
| Метрики приложения | Micrometer → Prometheus registry | Apache 2.0 |

## Структура

```
observability/
├── grafana/
│   └── eca-system-dashboard.json   # дашборд бизнес+технических метрик
├── prometheus/
│   └── alerts.yml                  # правила алертинга (DLQ, throughput, доставка)
├── slo.yml                         # определения SLO с числовыми целями
└── README.md                       # этот файл
```

## Grafana: импорт дашборда

1. Убедитесь, что источник данных **Prometheus** настроен в Grafana (имя: `Prometheus`).
2. Перейдите в Grafana → Dashboards → **Import**.
3. Загрузите `observability/grafana/eca-system-dashboard.json` (кнопка **Upload JSON file**).
4. Выберите источник данных Prometheus и нажмите **Import**.

Дашборд покрывает:
- **Бизнес**: активные инстансы последовательностей, размер DLQ, активные условия, throughput входящих, исходящие uplink/ground, уведомления.
- **Латентность**: p95/p99 обработки события движком ECA (histogram из `eca_execution_event_duration_seconds_bucket`).
- **WAIT-таймауты**: сработавшие WAIT-шаги ECA.
- **JVM**: heap memory (used/max).
- **Hikari**: active/idle/pending connections к PostgreSQL.

## Prometheus: alert-правила

Добавьте в `prometheus.yml`:

```yaml
rule_files:
  - "/etc/prometheus/rules/eca-alerts.yml"
```

Скопируйте `observability/prometheus/alerts.yml` в `/etc/prometheus/rules/eca-alerts.yml`.

Или через docker-compose volume:

```yaml
services:
  prometheus:
    volumes:
      - ./observability/prometheus/alerts.yml:/etc/prometheus/rules/eca-alerts.yml:ro
```

Не забудьте добавить job в `scrape_configs`:

```yaml
scrape_configs:
  - job_name: eca-system
    metrics_path: /actuator/prometheus
    static_configs:
      - targets: ['eca-backend:8080']
    # Аутентификация: /actuator/prometheus требует SYSTEM_ADMIN JWT (P4-1).
    # Вариант 1: базовая аутентификация через Spring Security (дополнительная конфигурация).
    # Вариант 2: network-level (prometheus в том же VPC/namespace, без auth на scrape).
```

## SLO

Определения в `observability/slo.yml`:

| ID | Название | Цель |
|----|----------|------|
| SLO-1 | Доступность приёма ACARS | 99.5% / 30 дней |
| SLO-2 | Доставка uplink/ground | 99.0% / 30 дней |
| SLO-3 | Латентность движка ECA p95 | < 500 мс / 1 час |
| SLO-4 | Доставка уведомлений | 95% / 30 дней |

## Health Probes (P5-3)

Spring Boot Actuator Kubernetes-style probes:

| Эндпоинт | Назначение | Auth | k8s probe |
|----------|-----------|------|-----------|
| `/actuator/health/liveness` | JVM alive | нет | livenessProbe |
| `/actuator/health/readiness` | DB + каналы | нет | readinessProbe |
| `/actuator/health/startup` | DB + migration | нет | startupProbe |
| `/actuator/health` (полный) | все компоненты | SYSTEM_ADMIN JWT | — |

Пример Kubernetes deployment:

```yaml
livenessProbe:
  httpGet:
    path: /actuator/health/liveness
    port: 8080
  initialDelaySeconds: 10
  periodSeconds: 10
  failureThreshold: 3

readinessProbe:
  httpGet:
    path: /actuator/health/readiness
    port: 8080
  initialDelaySeconds: 15
  periodSeconds: 5
  failureThreshold: 6

startupProbe:
  httpGet:
    path: /actuator/health/startup
    port: 8080
  initialDelaySeconds: 10
  periodSeconds: 5
  failureThreshold: 30   # ждём до 30×5=150 сек при длинных Flyway-миграциях
```

`readiness` DOWN при:
- PostgreSQL недоступен (`db` компонент)
- Circuit breaker на UPLINK/GROUND канале в состоянии `OPEN` (`integrationChannels` компонент)
- DLQ (dead_letter_messages NEW) > 100 записей (порог задаётся `app.health.dlq-critical-size`)

`liveness` не зависит от БД/каналов — только livenessState JVM.

## Настройка порога DLQ

Через переменную окружения (без пересборки):

```bash
APP_HEALTH_DLQ_CRITICAL_SIZE=200  # значение по умолчанию: 100
```

Или в application.yml / ConfigMap:

```yaml
app:
  health:
    dlq-critical-size: 200
```
