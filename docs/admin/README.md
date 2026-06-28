# Документация администратора ECA System

ECA System — отечественная промышленная замена модуля SITA AIRCOM Sequencer. Система обработки авиационных событий по сообщениям «борт-земля» (ACARS), движок правил ECA (Event-Condition-Action). Заказчик — ФГУП «ЗащитаИнфоТранс».

Версия документации: 1.0 (актуальна для релиза P8).

---

## Содержание

### Установка и конфигурация

| Документ | Описание |
|----------|----------|
| [installation.md](installation.md) | Требования к окружению, Docker Compose (dev), Helm/Kubernetes (prod), первый запуск |
| [configuration.md](configuration.md) | Переменные окружения, Helm values, профили Spring, leader election, HPA |

### Эксплуатация

| Документ | Описание |
|----------|----------|
| [operations.md](operations.md) | Запуск/остановка, rolling update, масштабирование, управление пользователями, мониторинг, логи |

### Ранбуки (операционные процедуры)

| Ранбук | Триггер |
|--------|---------|
| [runbooks/service-restart.md](runbooks/service-restart.md) | Плановый или принудительный перезапуск backend/frontend |
| [runbooks/db-backup-restore.md](runbooks/db-backup-restore.md) | Создание резервной копии, восстановление, PITR |
| [runbooks/incident-high-cpu.md](runbooks/incident-high-cpu.md) | CPU > 80% продолжительно, HPA достиг maxReplicas |
| [runbooks/incident-db-connection.md](runbooks/incident-db-connection.md) | Readiness probe 503, потеря соединения с PostgreSQL |
| [runbooks/leader-election-stuck.md](runbooks/leader-election-stuck.md) | Планировщики не тикают, нет лидера в логах |

---

## Глоссарий

| Термин | Определение |
|--------|-------------|
| Борт | Воздушное судно, идентифицируемое по tail number (AN) или рейсу (FI) |
| Рейс | Конкретный плановый полёт: flight id + бортовой номер + данные рейса |
| Последовательность (sequence) | Набор шагов ECA, привязанный к одному или нескольким бортам |
| Шаг | Элемент последовательности: ACTION, EVALUATE IF или WAIT FOR |
| Критерий | Условие оценки шага: message received, flight stage, position, time и т.д. |
| Условие (condition) | Именованный флаг, поднимаемый/закрываемый шагом ACTION |
| Алерт | Уведомление уровней No / Low / Medium / High / Critical |
| Экземпляр (instance) | Запущенная копия последовательности для конкретного борта |
| Leader election | Механизм выбора одной из реплик для выполнения фоновых задач |
| DLQ | Dead Letter Queue — очередь сообщений, не доставленных после N попыток |
| ECA | Event-Condition-Action — парадигма правил обработки событий |

---

## Архитектура (краткая)

```
[Борт ACARS] ──ACARS──▶ /api/acars/** (без JWT, сетевая защита mTLS/allowlist)
                              │
                    [eventprocessor module]
                              │
                    [execution module] ── leader election (PostgreSQL)
                         │       │
              [sequence module]  [integration module]
                                       │
                              [outbound messages → каналы UPLINK/GROUND]
```

Модули backend: `sequence`, `execution`, `eventprocessor`, `integration`, `user`.
Межмодульное взаимодействие: публичные API модулей + Spring Modulith Events (транзакционный outbox).

---

## Контакты и поддержка

При инцидентах, не описанных в ранбуках — эскалировать дежурному архитектору.
Новые задачи — через тикет-систему (тег `eca-system`).
