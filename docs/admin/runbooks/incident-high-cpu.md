# Ранбук: Инцидент — высокая загрузка CPU

**Уровень:** Warning (CPU > 80% в течение 5+ минут) / Critical (HPA достиг maxReplicas, CPU не снижается)

---

## Симптомы

- `process_cpu_usage` > 0.8 продолжительно
- HPA показывает `REPLICAS = maxReplicas` (8 в dev, 12 в prod)
- Метрика `http_server_requests_seconds_max` растёт (запросы замедляются)
- Возможны сбои readiness probe (при CPU 100% GC-паузы могут нарушить таймаут)
- В логах: частые GC-паузы (`GC overhead limit exceeded`) или stack overflow

---

## Диагностика

### Шаг 1. Проверить текущее состояние pod'ов и HPA

```bash
# Загрузка CPU по pod'ам
kubectl top pods -n eca

# Пример вывода:
# NAME                              CPU(cores)   MEMORY(bytes)
# eca-system-backend-xxx-aaa        1800m        1200Mi   ← аномально высокий
# eca-system-backend-xxx-bbb        600m         900Mi
# eca-system-backend-xxx-ccc        550m         850Mi

# Статус HPA
kubectl get hpa eca-system-backend-hpa -n eca
# NAME                    TARGETS    MINPODS   MAXPODS   REPLICAS
# eca-system-backend-hpa  85%/70%    3         12        10        ← HPA активно масштабирует
```

### Шаг 2. Определить источник нагрузки

```bash
# Снять thread dump проблемного pod'а (не прерывает работу)
kubectl exec -n eca <HIGH_CPU_POD> -- kill -3 1
kubectl logs -n eca <HIGH_CPU_POD> --tail=200 | grep -A 50 "Full thread dump"

# Или через jstack (если доступен в образе)
kubectl exec -n eca <HIGH_CPU_POD> -- jstack 1 > /tmp/thread_dump.txt
```

Что искать в thread dump:
- Потоки в состоянии `RUNNABLE` с вызовами бизнес-кода (не `parking`, не `waiting`) — указывают на горячий путь.
- `GarbageCollect` потоки в RUNNABLE — симптом давления на heap.
- `ForkJoinPool` или `parallel stream` — возможный параллелизм без ограничений.

### Шаг 3. Проверить метрики

```bash
kubectl port-forward -n eca deployment/eca-system-backend 8080:8080 &

# GC-паузы
curl -s 'http://localhost:8080/actuator/prometheus' | grep 'jvm_gc_pause'

# Heap utilization
curl -s 'http://localhost:8080/actuator/prometheus' | grep 'jvm_memory_used_bytes.*heap'

# Throughput входящих ACARS-сообщений
curl -s 'http://localhost:8080/actuator/prometheus' \
  | grep 'http_server_requests_seconds_count.*acars'

# Загрузка HikariCP (нет ли ожидания соединений)
curl -s 'http://localhost:8080/actuator/prometheus' | grep 'hikaricp'
```

### Шаг 4. Проверить очередь ACARS-сообщений и outbound

```sql
-- Накопление необработанных исходящих сообщений
SELECT status, count(*) FROM outbound_messages GROUP BY status;
-- Если PENDING много → проблема с отправкой, не с CPU

-- Активные экземпляры последовательностей
SELECT status, count(*) FROM execution_instances GROUP BY status;
-- Аномально большое RUNNING → возможно застрявшие экземпляры нагружают движок
```

---

## Ответные меры

### Меры уровня 1: масштабирование

```bash
# Немедленно увеличить minReplicas HPA (если CPU не успевает снизиться до maxReplicas)
kubectl patch hpa eca-system-backend-hpa -n eca \
  --type=merge \
  -p '{"spec":{"maxReplicas":16}}'

# Ручное масштабирование сверх текущего HPA-максимума
kubectl scale deployment eca-system-backend --replicas=6 -n eca
```

### Меры уровня 2: ограничение входящего потока

Если источник нагрузки — аномальный всплеск ACARS-сообщений от конкретного агрегатора:

```bash
# Временно заблокировать IP-источника через NetworkPolicy (пример)
cat <<EOF | kubectl apply -f -
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: block-acars-source
  namespace: eca
spec:
  podSelector:
    matchLabels:
      app.kubernetes.io/component: backend
  policyTypes:
    - Ingress
  ingress:
    - from:
        - ipBlock:
            cidr: 0.0.0.0/0
            except:
              - PROBLEMATIC_SOURCE_IP/32
EOF
```

После стабилизации — удалить NetworkPolicy: `kubectl delete networkpolicy block-acars-source -n eca`.

### Меры уровня 3: принудительный перезапуск проблемного pod'а

Если один pod с аномально высоким CPU (зависший GC, утечка):

```bash
kubectl delete pod <HIGH_CPU_POD> -n eca
# Deployment сразу создаст новый pod
```

### Меры уровня 4: настройка JVM (если воспроизводится регулярно)

Добавить JVM-флаги через Helm values (требует согласования с tech-lead и ADR при постоянном изменении):

```yaml
# В values-prod.yaml (пример — не применять без анализа)
backend:
  env:
    JAVA_OPTS: "-XX:+UseG1GC -XX:MaxGCPauseMillis=200 -XX:G1HeapRegionSize=16m"
```

---

## Критерии закрытия инцидента

Инцидент считается закрытым при выполнении всех условий:

| Метрика | Норма |
|---------|-------|
| `process_cpu_usage` | < 0.7 (70%) на всех репликах в течение 10 минут |
| HPA REPLICAS | Начало scale-down (≤ maxReplicas × 0.7) |
| `http_server_requests_seconds_max` | < 1 с |
| Readiness probe | `{"status":"UP"}` на всех репликах |
| Thread dump | Нет потоков в RUNNABLE с аномальными стек-трейсами |

### После закрытия

1. Сохранить thread dump и метрики в тикет инцидента.
2. Проверить, не потеряны ли ACARS-сообщения (DLQ, `outbound_messages` со status FAILED).
3. Если проблема воспроизводится — создать тикет для профилирования (тег `perf`).
4. Восстановить `hpa.maxReplicas` до нормального значения, если было увеличено временно:

```bash
kubectl patch hpa eca-system-backend-hpa -n eca \
  --type=merge \
  -p '{"spec":{"maxReplicas":12}}'
```
