# Ранбук: Перезапуск сервиса ECA System

**Применимость:** плановый перезапуск, применение новой конфигурации, устранение зависания одного pod'а.

---

## Когда использовать

- Применение изменений ConfigMap или Secret без обновления образа.
- Подозрение на утечку памяти или зависание одного из pod'ов.
- Плановое обслуживание (смена TLS-сертификата, ротация JWT-ключа).
- После изменения параметров, не подхватываемых на лету (например, `SPRING_PROFILES_ACTIVE`).

---

## Graceful Restart в Kubernetes

### Вариант 1 — Rolling restart всего Deployment (рекомендуется)

```bash
# Перезапустить backend (rolling, zero-downtime)
kubectl rollout restart deployment/eca-system-backend -n eca

# Следить за ходом
kubectl rollout status deployment/eca-system-backend -n eca
# Ожидать: "successfully rolled out"

# Перезапустить frontend (если нужно)
kubectl rollout restart deployment/eca-system-frontend -n eca
kubectl rollout status deployment/eca-system-frontend -n eca
```

Механизм: Kubernetes поочерёдно создаёт новые pod'ы, ждёт их перехода в Ready (startup + readiness probe), затем останавливает старые. `maxUnavailable: 0` гарантирует отсутствие downtime.

Каждый pod перед получением SIGTERM выполняет `preStop: sleep 5` — это даёт Kubernetes время снять pod с endpoints Service до начала завершения.

### Вариант 2 — Перезапуск одного pod'а

Использовать если только один pod ведёт себя аномально (зависший GC, нетипичный CPU).

```bash
# Найти проблемный pod
kubectl get pods -n eca -l app.kubernetes.io/component=backend

# Удалить pod — Deployment немедленно создаст новый
kubectl delete pod <POD_NAME> -n eca

# Убедиться, что новый pod поднялся
kubectl get pods -n eca -w
```

---

## Принудительный перезапуск (при зависании pod)

Если pod не отвечает на SIGTERM и не завершается за `terminationGracePeriodSeconds` (40 с):

```bash
# Принудительное удаление (SIGKILL, без ожидания graceful)
kubectl delete pod <POD_NAME> -n eca --grace-period=0 --force
```

**Внимание:** принудительное удаление может привести к потере in-flight ACARS-сообщений и незавершённому освобождению лидерства. После этого лидер будет выбран заново после истечения TTL аренды (~30 с).

---

## Перезапуск в Docker Compose

```bash
# Graceful (SIGTERM → ждёт graceful-shutdown-phase 25 с)
docker compose restart app

# Принудительный перезапуск с пересборкой образа
docker compose up -d --build app

# Перезапуск только PostgreSQL (вызовет кратковременный downtime app)
docker compose restart postgres
```

---

## Что проверить после перезапуска

### 1. Health проверки

```bash
# K8s
kubectl port-forward -n eca deployment/eca-system-backend 8080:8080 &
curl -s http://localhost:8080/actuator/health/liveness   # {"status":"UP"}
curl -s http://localhost:8080/actuator/health/readiness  # {"status":"UP"}

# Docker Compose
curl -s http://localhost:8080/actuator/health  # {"status":"UP"}
```

### 2. Все pod'ы в Running/Ready

```bash
kubectl get pods -n eca
# Все pod'ы должны быть READY 1/1 (backend) или 1/1 (frontend)
```

### 3. Flyway применил миграции (только при обновлении)

```bash
kubectl port-forward -n eca deployment/eca-system-backend 8080:8080 &
# Или прямо в БД:
psql -h $DB_HOST -U eca_user -d eca_db \
  -c "SELECT version, description, success, installed_on FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 3;"
```

### 4. Leader election восстановлен

```sql
SELECT holder_id, lease_until,
       EXTRACT(EPOCH FROM (lease_until - NOW())) AS ttl_sec
FROM leader_election
WHERE lock_name = 'scheduler';
-- ttl_sec должен быть положительным (> 0)
```

### 5. Проверить логи на ошибки старта

```bash
# K8s
kubectl logs -n eca <NEW_POD_NAME> --tail=50

# Docker Compose
docker logs eca-app --tail=50
```

Нормальный старт завершается строкой вида:
```
Started EcaSystemApplication in X.XXX seconds
```

---

## Откат при неудачном перезапуске

```bash
# Если после `helm upgrade` + restart что-то пошло не так
helm rollback eca -n eca

# Следить за откатом
kubectl rollout status deployment/eca-system-backend -n eca
```
