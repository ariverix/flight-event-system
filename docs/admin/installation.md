# Руководство по установке ECA System

## Содержание

1. [Требования к окружению](#требования-к-окружению)
2. [Предварительные условия](#предварительные-условия)
3. [Установка через Docker Compose (dev/test)](#установка-через-docker-compose)
4. [Установка через Helm / Kubernetes (staging/prod)](#установка-через-helm--kubernetes)
5. [Инициализация секретов](#инициализация-секретов)
6. [Проверка установки](#проверка-установки)

---

## Требования к окружению

### Минимальные (dev/test — Docker Compose)

| Ресурс | Требование |
|--------|-----------|
| OS | Linux (RHEL 8+, Ubuntu 22.04+), macOS 13+, Windows 11 с WSL2 |
| CPU | 4 ядра (x86_64) |
| RAM | 4 ГБ свободной памяти |
| Диск | 10 ГБ свободного места (образы Docker + данные PostgreSQL) |
| Сеть | Порт 8080 (HTTP), 5432 (PostgreSQL, опционально — только для dev-доступа) |
| Docker | Docker Engine 24.0+ или Docker Desktop 4.20+ |

### Минимальные (staging/prod — Kubernetes)

| Ресурс | Требование |
|--------|-----------|
| Kubernetes | v1.28+ |
| Helm | v3.12+ |
| Узлы backend | 2+ узла, на каждом: 2 vCPU, 2 ГБ RAM (для 3 реплик с HPA) |
| Узел PostgreSQL | Внешняя управляемая PostgreSQL 16 (StatefulSet внутри кластера — только dev) |
| Диск для данных | 50+ ГБ (SSD) для PostgreSQL с учётом партиций tracking_event_log |
| Ingress | nginx-ingress-controller v1.9+ |
| Сеть | FQDN для ingress, TLS-сертификат (cert-manager или ручной) |

### Сетевые требования (prod)

- ACARS-канал (входящие сообщения) закрыт на уровне сети: только через mTLS/allowlist. Порт `/api/acars/**` не требует JWT, но должен быть доступен только с доверенных ACARS-агрегаторов.
- Остальные API (`/api/**`) — за JWT. Принимаются только по HTTPS в prod.
- WebSocket (`/ws/eca`) — поддерживается nginx-ingress с настроенными `proxy-read-timeout: 3600`.

---

## Предварительные условия

### Для Docker Compose

```bash
# Проверить наличие Docker и Compose
docker --version        # >= 24.0
docker compose version  # >= 2.20
```

### Для Kubernetes / Helm

```bash
# Проверить версию кластера
kubectl version --short

# Проверить наличие Helm
helm version --short    # >= v3.12

# Проверить наличие nginx-ingress-controller
kubectl get pods -n ingress-nginx
```

---

## Установка через Docker Compose

Этот режим предназначен для разработки и тестирования. PostgreSQL запускается вместе с приложением в одном Compose-стеке.

### Шаг 1. Клонировать репозиторий

```bash
git clone https://github.com/ariverix/flight-event-system.git
cd flight-event-system
```

### Шаг 2. Создать файл переменных окружения (опционально)

По умолчанию используются dev-значения из `docker-compose.yml`. Для переопределения:

```bash
cat > .env << 'EOF'
DB_NAME=eca_db
DB_USERNAME=eca_user
DB_PASSWORD=eca_password
JWT_SECRET=eca-jwt-secret-key-for-development-minimum-256-bits-long
EOF
```

### Шаг 3. Запустить стек

```bash
docker compose up --build
```

Первый запуск занимает 3–5 минут: сборка образов, применение Flyway-миграций (V1–V37+).

### Шаг 4. Дождаться готовности

```bash
# Наблюдать за логами до появления "Started EcaSystemApplication"
docker compose logs -f app

# Проверить health
curl -s http://localhost:8080/actuator/health | python3 -m json.tool
# Ожидаемый ответ: {"status":"UP", ...}
```

### Остановка

```bash
docker compose down          # остановить без удаления данных
docker compose down -v       # остановить и удалить тома (данные БД будут потеряны)
```

---

## Установка через Helm / Kubernetes

### Шаг 1. Создать namespace

```bash
kubectl create namespace eca
```

### Шаг 2. Создать секреты Kubernetes

Секреты не должны храниться в git. Передавать через CLI или External Secrets Operator.

**Вариант A — создать Secret вручную:**

```bash
# Секрет базы данных
kubectl create secret generic eca-system-db \
  --namespace eca \
  --from-literal=url='jdbc:postgresql://PROD_DB_HOST:5432/eca_db' \
  --from-literal=username='eca_user' \
  --from-literal=password='PROD_DB_PASSWORD'

# Секрет JWT
kubectl create secret generic eca-system-jwt \
  --namespace eca \
  --from-literal=secret='PROD_JWT_SECRET_MIN_32_CHARS_REPLACE_THIS'
```

**Вариант B — через `--set` при `helm install`** (параметры передаются в шаблоны `templates/secret.yaml`):

```bash
helm install eca deploy/helm/eca-system \
  --namespace eca \
  -f deploy/helm/eca-system/values-prod.yaml \
  --set externalDatabase.url='jdbc:postgresql://PROD_DB_HOST:5432/eca_db' \
  --set externalDatabase.username='eca_user' \
  --set externalDatabase.password='PROD_DB_PASSWORD' \
  --set jwt.secret='PROD_JWT_SECRET_MIN_32_CHARS_REPLACE_THIS'
```

### Шаг 3. Подготовить prod PostgreSQL

Внешняя управляемая PostgreSQL 16. В prod встроенный StatefulSet отключён (`postgresql.enabled: false` в `values-prod.yaml`).

```sql
-- Выполнить от суперпользователя PostgreSQL
CREATE DATABASE eca_db;
CREATE USER eca_user WITH PASSWORD 'PROD_DB_PASSWORD';
GRANT ALL PRIVILEGES ON DATABASE eca_db TO eca_user;

-- Для партиций tracking_event_log (Flyway V37) нужны права CREATE
ALTER USER eca_user CREATEDB;
```

Flyway применит все миграции автоматически при первом старте приложения.

### Шаг 4. Установить Helm chart

```bash
helm install eca deploy/helm/eca-system \
  --namespace eca \
  -f deploy/helm/eca-system/values-prod.yaml \
  --set externalDatabase.password='PROD_DB_PASSWORD' \
  --set jwt.secret='PROD_JWT_SECRET_MIN_32_CHARS_REPLACE_THIS' \
  --wait \
  --timeout=5m
```

Флаг `--wait` ждёт перехода всех pod'ов в Ready (включая прохождение startup probe с Flyway).

### Шаг 5. Проверить состояние деплоя

```bash
# Статус pod'ов
kubectl get pods -n eca

# Ожидаемый вывод (3 backend + 2 frontend):
# NAME                              READY   STATUS    RESTARTS   AGE
# eca-system-backend-xxx-yyy        1/1     Running   0          2m
# eca-system-backend-xxx-zzz        1/1     Running   0          2m
# eca-system-backend-xxx-www        1/1     Running   0          2m
# eca-system-frontend-xxx-yyy       1/1     Running   0          2m
# eca-system-frontend-xxx-zzz       1/1     Running   0          2m

# Health через port-forward (actuator закрыт публично в prod)
kubectl port-forward -n eca deployment/eca-system-backend 8080:8080 &
curl -s http://localhost:8080/actuator/health/readiness
```

---

## Инициализация секретов

### Структура K8s Secret для БД

Helm-шаблон `templates/secret.yaml` создаёт два Secret:

| Имя Secret | Ключи |
|-----------|-------|
| `eca-system-db` | `url`, `username`, `password` |
| `eca-system-jwt` | `secret` |

Имена Secret можно переопределить через `backend.dbSecretName` и `backend.jwtSecretName` в values.yaml (для интеграции с External Secrets Operator или Vault).

### JWT Secret

Минимальная длина — 32 ASCII-символа (256 бит). Генерация безопасного значения:

```bash
openssl rand -base64 48 | tr -d '\n'
```

Срок действия access-токена: 15 минут (`JWT_EXPIRATION_MS=900000`).
Срок действия refresh-токена: 7 дней (`JWT_REFRESH_EXPIRATION_MS=604800000`).

### Смена пароля администратора после установки

После первого запуска в системе присутствует пользователь `admin` с паролем `admin` (BCrypt, миграция V8). Немедленно сменить через API:

```bash
# Получить access-токен
TOKEN=$(curl -s -X POST http://eca.example.ru/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"admin"}' | python3 -c "import sys,json; print(json.load(sys.stdin)['accessToken'])")

# Сменить пароль
curl -s -X PATCH http://eca.example.ru/api/users/1 \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"password":"NEW_SECURE_PASSWORD"}'
```

---

## Проверка установки

### Smoke test

```bash
# 1. Получить токен (dev)
TOKEN=$(curl -s -X POST http://localhost:8080/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"admin"}' | python3 -c "import sys,json; print(json.load(sys.stdin)['accessToken'])")

# 2. Проверить список последовательностей
curl -s -H "Authorization: Bearer $TOKEN" \
  http://localhost:8080/api/sequences | python3 -m json.tool

# 3. Убедиться, что Flyway применил все миграции
psql -h localhost -U eca_user -d eca_db \
  -c "SELECT version, description, success FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 5;"

# 4. Проверить health группы
curl -s http://localhost:8080/actuator/health/liveness    # {"status":"UP"}
curl -s http://localhost:8080/actuator/health/readiness   # {"status":"UP"}
curl -s http://localhost:8080/actuator/health/startup     # {"status":"UP"}
```

### Создание тестовой последовательности через API

```bash
# Создать последовательность для борта VP-BQR (демо-сценарий из V14)
curl -s -X POST http://localhost:8080/api/sequences \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{
    "name": "Test Sequence",
    "description": "Smoke test after installation",
    "aircraftTailNumber": "VP-BQR",
    "active": true
  }' | python3 -m json.tool
# Ожидаемый ответ: объект последовательности с id
```

После успешного smoke test установка считается завершённой.
