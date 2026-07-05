# flight-event-system (ECA System)

Отечественная промышленная замена модуля **SITA AIRCOM Sequencer** — система обработки
авиационных событий по сообщениям «борт-земля» (ACARS) на движке правил ECA
(Event-Condition-Action). Заказчик — ФГУП «ЗащитаИнфоТранс».

## Быстрый старт (docker-compose)

```bash
docker compose up --build
```

- UI/API: http://localhost:8081 (порт хоста; внутри контейнера приложение слушает 8080)
- Демо-логин: `admin` / `admin` (заменить после установки — см. `docs/admin/installation.md`)
- Swagger UI: http://localhost:8081/swagger-ui.html (за RBAC SYSTEM_ADMIN)
- Health: http://localhost:8081/actuator/health/{liveness,readiness,startup}

Стек: Java 21 / Spring Boot 3.5 / Spring Modulith / PostgreSQL 16 / Flyway (V1–V38);
React 18 / TypeScript 5 / Ant Design 5 / React Flow 12. Подробности: `CLAUDE.md`,
установка и эксплуатация — `docs/admin/`, готовность — `PRODUCTION_READINESS_REPORT.md`.

---

# Команда агентов flight-event-system — промышленное внедрение

Полный пакет для Claude Code: 19 автономных агентов + общий «мозг» + роадмап до промышленной замены SITA Sequencer + пошаговый план.

## Как запустить (по твоей методике — по одному промту)
1. Скопируй содержимое пакета в корень репозитория `flight-event-system` (файлы `CLAUDE.md`, `ROADMAP.md`, `TEAM.md` и папку `.claude/agents/`).
2. Открой Claude Code в корне репо. Вставь ОДНИМ сообщением весь `00_FACTORY_PROMPT.md` — поднимется команда + режим оркестратора, выведется план P0.
3. Дальше кидай промты из `RUN_PLAN.md` ПО ОДНОМУ: `поехали P0-1`, ждёшь зелёный отчёт + reviewer PASS, потом `P0-2` и т.д.

## Что внутри
- `00_FACTORY_PROMPT.md` — мастер-промт (вставляешь первым).
- `RUN_PLAN.md` — пронумерованные промты P0-1 … P8-4 (по одному).
- `CLAUDE.md` — инварианты/стек/спека паритета SITA/протокол (Claude Code грузит автоматом).
- `ROADMAP.md` — фазы P0–P8 с приёмкой.
- `TEAM.md` — реестр 19 агентов + маршрутизация.
- `.claude/agents/*.md` — сами агенты.

## Команда (19)
Координация: tech-lead, architect.
Backend: backend-dev, sequence-engine-dev, integration-dev, templates-dev, alerts-dev, db-dev.
Качество: qa-agent, test-engineer, reviewer, bug-fixer.
Безопасность/инфра/наблюдаемость: security-agent, devops-agent, observability-agent.
Frontend: frontend-architect, ui-agent.
Доки/соответствие: docs-agent, compliance-agent.

`feature-agent` ретайрнут — заменён специалистами.
