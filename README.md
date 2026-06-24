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
