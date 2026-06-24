# TEAM.md — команда агентов flight-event-system

19 агентов в `.claude/agents/`. Главная сессия Claude Code работает как **оркестратор**: читает задачу → выбирает агента ниже → делегирует → собирает результат → гонит через `reviewer`.

## Координация
| Агент | Когда звать | Tools |
|---|---|---|
| `tech-lead` | Начало фазы/эпика: декомпозиция, ledger, контроль DoD и гейтов. Не пишет прод-код. | Read, Write, Bash, Glob, Grep |
| `architect` | Кросс-модульные изменения, новые подсистемы, ADR, границы Modulith. | Read, Write, Bash, Glob, Grep |

## Backend
| Агент | Зона | Tools |
|---|---|---|
| `backend-dev` | Общие фичи/API/CRUD/доменка вне узких специалистов. | Read, Write, Edit, Bash, Glob, Grep |
| `sequence-engine-dev` | Ядро ECA: шаги, критерии, решения, стейт-машина инстанса, планировщик, resume, конкурентность. | Read, Write, Edit, Bash, Glob, Grep |
| `integration-dev` | ACARS/AFTN/Type B, ARINC-парсинг, позывные+matching table, позиции, DLQ/ретраи. | Read, Write, Edit, Bash, Glob, Grep |
| `templates-dev` | Шаблоны сообщений + custom fields. | Read, Write, Edit, Bash, Glob, Grep |
| `alerts-dev` | Условия/алерты, уровни, event handling, уведомления. | Read, Write, Edit, Bash, Glob, Grep |
| `db-dev` | Схема, Flyway, индексы, партиционирование, retention, бэкап. ВСЕ изменения схемы — через него. | Read, Write, Edit, Bash, Glob, Grep |

## Качество
| Агент | Зона | Tools |
|---|---|---|
| `qa-agent` | Юнит-тесты, покрытие ≥85%, edge-кейсы. | Read, Write, Edit, Bash, Glob, Grep |
| `test-engineer` | Интеграционные/контрактные/e2e/нагрузочные (Testcontainers, k6/Gatling), resume-тест. | Read, Write, Edit, Bash, Glob, Grep |
| `reviewer` | Обязательный гейт перед `done`. Только читает. | Read, Bash, Glob, Grep |
| `bug-fixer` | Красные тесты, регрессии, замечания ревью. | Read, Write, Edit, Bash, Glob, Grep |

## Безопасность / Инфра / Наблюдаемость
| Агент | Зона | Tools |
|---|---|---|
| `security-agent` | RBAC, JWT+refresh, секреты, TLS/ГОСТ, OWASP/SAST, аудит. | Read, Write, Edit, Bash, Glob, Grep |
| `devops-agent` | Docker→k8s/Helm, CI/CD, HA/кластер, leader election, релизы. | Read, Write, Edit, Bash, Glob, Grep |
| `observability-agent` | Метрики/трейсы/логи, health, дашборды, SLO. | Read, Write, Edit, Bash, Glob, Grep |

## Frontend
| Агент | Зона | Tools |
|---|---|---|
| `frontend-architect` | Каркас FE, стор, API-клиент из OpenAPI, WebSocket, i18n, дизайн-система. | Read, Write, Edit, Bash, Glob, Grep |
| `ui-agent` | Реализация экранов, редактор React Flow, UX/анимации, таймлайн. | Read, Write, Edit, Bash, Glob, Grep |

## Документы / Соответствие
| Агент | Зона | Tools |
|---|---|---|
| `docs-agent` | OpenAPI, руководство админа (RU), ранбуки, README, ADR-сводка. | Read, Write, Edit, Bash, Glob, Grep |
| `compliance-agent` | Импортозамещение/Реестр, ФСТЭК-чеклист, лицензии, матрица паритета SITA. | Read, Write, Bash, Glob, Grep |

## Маршрутизация (быстрый справочник)
- Меняем схему БД → `db-dev`. Движок → `sequence-engine-dev`. Сообщения «борт-земля» → `integration-dev`.
- Кросс-модуль/новая подсистема → сначала `architect`, потом профильный dev.
- После любой реализации → `qa-agent`/`test-engineer` → `reviewer`. Красное → `bug-fixer`.
- Не уверен, кто делает → `tech-lead` декомпозирует и назначит.

## Ретайр
Старый `feature-agent` (BE+FE «всё подряд») заменён специалистами выше — больше не используем, можно удалить его файл.
