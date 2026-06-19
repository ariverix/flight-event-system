# Architecture Decision Records

ADR фиксируют кросс-модульные и стратегические технические решения проекта
`flight-event-system`. Новые ADR — по шаблону `ADR-template.md`, нумерация
сквозная, статус не меняется задним числом — добавляется новый ADR со
статусом `Superseded by ADR-XXXX` у старого при пересмотре решения.

Когда заводить ADR — см. роль `architect` в `CLAUDE.md` / `.claude/agents/`:
новая подсистема, изменение, затрагивающее >1 модуль или публичный контракт
между модулями, спор вида «монолит vs микросервисы», «event bus vs Outbox».

## Индекс

| № | Название | Статус |
|---|---|---|
| [ADR-0001](ADR-0001-modular-monolith-vs-microservices.md) | Модульный монолит (Spring Modulith) против микросервисов | Accepted |
