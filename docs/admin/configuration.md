# Параметры конфигурации ECA System

## Содержание

1. [Переменные окружения](#переменные-окружения)
2. [Helm values](#helm-values)
3. [Профили Spring](#профили-spring)
4. [Leader Election](#leader-election)
5. [HikariCP (пул соединений)](#hikaricp-пул-соединений)
6. [HPA — автомасштабирование](#hpa--автомасштабирование)
7. [CORS для frontend](#cors-для-frontend)
8. [Retention (удержание данных)](#retention-удержание-данных)
9. [Трассировка (OpenTelemetry)](#трассировка-opentelemetry)

---

## Переменные окружения

Все переменные читаются Spring Boot при старте. В Docker Compose задаются в секции `environment` или через `.env`. В Kubernetes — через ConfigMap (несекретные) и Secret (чувствительные).

### Подключение к базе данных

| Переменная | Обязательная | По умолчанию (dev) | Описание |
|-----------|:---:|---|---|
| `DB_URL` | Да | `jdbc:postgresql://localhost:5432/eca_db` | JDBC URL PostgreSQL |
| `DB_USERNAME` | Да | `eca_user` | Пользователь PostgreSQL |
| `DB_PASSWORD` | Да | `eca_password` | Пароль PostgreSQL. **В prod — только через Secret** |

### JWT и аутентификация

| Переменная | Обязательная | По умолчанию (dev) | Описание |
|-----------|:---:|---|---|
| `JWT_SECRET` | Да | `eca-jwt-secret-key-for-development-minimum-256-bits-long` | Ключ подписи JWT. Минимум 32 символа (256 бит). **Обязательно заменить в prod** |
| `JWT_EXPIRATION_MS` | Нет | `900000` | Время жизни access-токена, мс (15 минут) |
| `JWT_REFRESH_EXPIRATION_MS` | Нет | `604800000` | Время жизни refresh-токена, мс (7 дней) |

### HikariCP (пул соединений)

| Переменная | По умолчанию | Описание |
|-----------|---|---|
| `DB_POOL_MAX` | `20` | Максимальное число соединений в пуле. `N_replicas × DB_POOL_MAX < PostgreSQL max_connections` |
| `DB_POOL_MIN_IDLE` | `5` | Минимальное число простаивающих соединений |

В Kubernetes ConfigMap эти значения дополнительно управляются через:

| Переменная ConfigMap | По умолчанию | Описание |
|---------------------|---|---|
| `SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE` | `10` | Переопределяет `DB_POOL_MAX` в K8s (Helm dev-default) |
| `SPRING_DATASOURCE_HIKARI_MINIMUM_IDLE` | `2` | Переопределяет `DB_POOL_MIN_IDLE` в K8s |
| `SPRING_DATASOURCE_HIKARI_CONNECTION_TIMEOUT` | `30000` | Таймаут получения соединения из пула, мс |
| `SPRING_DATASOURCE_HIKARI_IDLE_TIMEOUT` | `600000` | Время до закрытия простаивающего соединения, мс |
| `SPRING_DATASOURCE_HIKARI_MAX_LIFETIME` | `1800000` | Максимальное время жизни соединения, мс |
| `SPRING_DATASOURCE_HIKARI_KEEPALIVE_TIME` | `60000` | Интервал keepalive-запроса к БД, мс |

### Сервер и Spring

| Переменная | По умолчанию | Описание |
|-----------|---|---|
| `SPRING_PROFILES_ACTIVE` | `dev` | Активный профиль: `dev` или `prod` |
| `SERVER_PORT` | `8080` | HTTP-порт приложения |
| `SERVER_SHUTDOWN` | `graceful` | Режим остановки: ждёт завершения in-flight запросов |
| `SPRING_LIFECYCLE_TIMEOUT_PER_SHUTDOWN_PHASE` | `30s` | Таймаут graceful shutdown |

### Retention (хранение данных)

| Переменная | По умолчанию | Описание |
|-----------|---|---|
| `RETENTION_TEL_MONTHS` | `6` | Хранить партиции `tracking_event_log` N месяцев |
| `RETENTION_MESSAGES_DAYS` | `90` | Хранить записи `messages` N дней |
| `RETENTION_AUDIT_DAYS` | `365` | Хранить записи `audit_log` N дней |
| `RETENTION_CREATE_AHEAD_MONTHS` | `3` | Создавать партиции `tracking_event_log` вперёд на N месяцев |
| `RETENTION_CRON` | `0 0 3 * * *` | Расписание задачи retention (Spring cron: каждый день в 03:00) |

### Трассировка

| Переменная | По умолчанию | Описание |
|-----------|---|---|
| `TRACING_SAMPLING_PROBABILITY` | `1.0` | Доля сэмплируемых трейсов (1.0 = 100%). В prod с высокой нагрузкой понизить до 0.1–0.01 |
| `OTLP_TRACING_ENDPOINT` | `http://localhost:4318/v1/traces` | URL OpenTelemetry Collector |
| `OTLP_TRACING_ENABLED` | `false` | Включить экспорт трейсов в коллектор. В dev/CI отключено |

### Frontend runtime-переменные

Инжектируются через `entrypoint.sh` → `window.__env__` и доступны как `window.__env__.VITE_WS_URL`.

| Переменная | По умолчанию (dev) | По умолчанию (prod) | Описание |
|-----------|---|---|---|
| `VITE_WS_URL` | `ws://eca.local/ws/eca` | `wss://eca.example.ru/ws/eca` | WebSocket URL для клиентского подключения |

### Идентификация реплики (Kubernetes)

Задаются автоматически через `fieldRef` в Helm Deployment, не требуют ручного задания:

| Переменная | Источник | Описание |
|-----------|---|---|
| `POD_NAME` | `metadata.name` | Уникальный идентификатор pod'а. Используется в leader election как `holder_id` |
| `POD_NAMESPACE` | `metadata.namespace` | Namespace pod'а |

---

## Helm values

Helm chart находится в `deploy/helm/eca-system/`. Файлы values:

| Файл | Назначение |
|------|-----------|
| `values.yaml` | Dev-дефолты. Все параметры с описанием |
| `values-staging.yaml` | Переопределения для staging |
| `values-prod.yaml` | Переопределения для production |

### Ключевые параметры values.yaml

```yaml
backend:
  replicaCount: 2          # Количество реплик (min 2 для HA + RollingUpdate)
  springProfile: dev       # Активный профиль Spring

  resources:
    requests:
      cpu: "500m"
      memory: "768Mi"
    limits:
      cpu: "2"
      memory: "1536Mi"

  # Имена существующих K8s Secret (пусто = создать из chart)
  dbSecretName: ""
  jwtSecretName: ""

postgresql:
  enabled: true            # ОТКЛЮЧИТЬ в staging/prod (внешняя БД)

externalDatabase:          # Заполняется когда postgresql.enabled=false
  url: ""
  username: ""
  password: ""

jwt:
  secret: "REPLACE_ME_..."  # Обязательно заменить

ingress:
  enabled: true
  host: eca.local
  exposeActuator: true     # false в prod
  tls: []                  # Заполнить в prod (cert-manager или ручной)

hpa:
  enabled: true
  minReplicas: 2
  maxReplicas: 8
  targetCPUUtilizationPercentage: 70
```

### Переопределение параметров при деплое

```bash
# Передать секреты без записи в git
helm upgrade --install eca deploy/helm/eca-system \
  --namespace eca \
  -f deploy/helm/eca-system/values-prod.yaml \
  --set externalDatabase.password="$(cat /run/secrets/db_password)" \
  --set jwt.secret="$(cat /run/secrets/jwt_secret)"
```

---

## Профили Spring

### Профиль `dev`

Файл: `backend/src/main/resources/application-dev.yml`

- Уровень логирования `ru.protectinfotrans`: DEBUG
- Уровень `org.springframework`: INFO
- Структурированное логирование: отключено (human-readable)
- Hikari pool: max 20 соединений, min-idle 5

### Профиль `prod`

Активируется через `SPRING_PROFILES_ACTIVE=prod` или Helm `backend.springProfile: prod`.

- Уровень логирования `ru.protectinfotrans`: INFO
- Уровень `org.springframework`: WARN
- Структурированное логирование: JSON (ECS-формат), парсится Loki/ELK
- Actuator `/actuator/**`: доступен только внутри кластера (`ingress.exposeActuator: false`)
- HikariCP: параметры из ConfigMap (Helm prod-override)

Переключение между профилями не требует пересборки образа: только пересоздание pod'ов с новым значением `SPRING_PROFILES_ACTIVE`.

---

## Leader Election

Leader Election — механизм выбора одной из реплик backend для выполнения `@Scheduled`-задач (таймауты WAIT, доставка исходящих). Реализован на PostgreSQL (таблица `leader_election`, миграция V36). Подробнее: [ADR-0004](../adr/ADR-0004-leader-election.md).

### Принцип работы

Каждая реплика идентифицируется переменной `POD_NAME` (уникальное имя pod'а). Лидер — тот, чья строка-аренда в `leader_election` актуальна (`lease_until > NOW()`). Heartbeat каждые 10 секунд. При падении лидера — аренда протухает, другая реплика перехватывает.

### Настраиваемые параметры

Параметры находятся в `application.yml` и задаются через env-переменные (если нужно переопределение — добавить соответствующие `${ENV_VAR:default}` в конфигурацию):

| Параметр | Значение | Описание |
|---------|---|---|
| Heartbeat interval | 10 с | Интервал продления аренды лидером |
| TTL аренды | ~30 с | После протухания другая реплика перехватывает лидерство |
| Graceful release | При `@PreDestroy` | Лидер удаляет строку при штатной остановке → мгновенный перехват |

### Диагностика текущего лидера

```sql
SELECT holder_id, lock_name, lease_until,
       CASE WHEN lease_until > NOW() THEN 'ACTIVE' ELSE 'EXPIRED' END AS status
FROM leader_election;
```

---

## HikariCP (пул соединений)

Расчёт `SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE` для prod:

```
N_replicas × max_pool_size < PostgreSQL max_connections
```

Пример: 3 реплики × 10 соединений = 30 < 100 (дефолт PostgreSQL). Для нагрузки >200 msg/s рекомендуется увеличить до 20 (проверить `pg_stat_activity`):

```sql
-- Текущие активные соединения от приложения
SELECT count(*) FROM pg_stat_activity WHERE application_name = 'eca-hikari';
```

---

## HPA — автомасштабирование

HPA (`HorizontalPodAutoscaler`) масштабирует Deployment backend по CPU. Настраивается в `values.yaml` → `hpa.*`.

| Параметр | Dev | Prod | Описание |
|---------|---|---|---|
| `hpa.minReplicas` | 2 | 3 | Минимум реплик. Не меньше 2 (RollingUpdate maxUnavailable:0) |
| `hpa.maxReplicas` | 8 | 12 | Максимум реплик |
| `hpa.targetCPUUtilizationPercentage` | 70 | 70 | Порог CPU (% от requests.cpu) для scale-out |
| `hpa.scaleUp.stabilizationWindowSeconds` | 60 | 60 | Окно стабилизации перед scale-up, с |
| `hpa.scaleDown.stabilizationWindowSeconds` | 300 | 300 | Окно стабилизации перед scale-down, с |

**Логика trigger'а:** 70% × 500m requests.cpu = 350m avg CPU на pod → HPA добавляет реплику.

Leader election гарантирует корректность: при любом количестве реплик `@Scheduled`-задачи выполняются только на одной (лидере).

---

## CORS для frontend

CORS-конфигурация задаётся в коде (`SecurityConfig`). По умолчанию разрешены запросы с origin, соответствующего хосту ingress. Для dev localhost добавлен явно.

Для prod: если frontend и backend обслуживаются одним ingress-хостом — CORS не нужен (same-origin). При раздельных доменах добавить переменную окружения (проконсультироваться с архитектором, тип изменения требует ADR или тикета).

---

## Retention (удержание данных)

Задача retention запускается по cron (`RETENTION_CRON`, дефолт — каждый день в 03:00) только на реплике-лидере. Выполняет:

1. Удаление партиций `tracking_event_log` старше `RETENTION_TEL_MONTHS` месяцев.
2. Создание партиций `tracking_event_log` вперёд на `RETENTION_CREATE_AHEAD_MONTHS` месяцев.
3. Удаление записей `messages` старше `RETENTION_MESSAGES_DAYS` дней.
4. Удаление записей `audit_log` старше `RETENTION_AUDIT_DAYS` дней.

Проверить последнее выполнение retention:

```sql
-- Актуальные партиции tracking_event_log
SELECT schemaname, tablename, pg_size_pretty(pg_total_relation_size(schemaname||'.'||tablename))
FROM pg_tables
WHERE tablename LIKE 'tracking_event_log_%'
ORDER BY tablename;
```
