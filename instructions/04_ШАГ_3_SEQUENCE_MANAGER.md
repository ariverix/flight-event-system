# ШАГ 3: SEQUENCE MANAGER (CRUD последовательностей и шагов)

## Что сделать:

Реализация UC-01 (Создать последовательность), UC-02 (Добавить шаг), UC-03 (Настроить переходы), UC-04 (Активировать последовательность).

**Порты:**
- `SequenceManagementUseCase` (port/in/) — входной интерфейс с методами CRUD
- `SequenceRepositoryPort` (port/out/) — интерфейс для хранения

**Сервис:**
- `SequenceService` (application/) — реализация бизнес-логики
- `SequenceValidator` (application/) — отдельный класс для валидации перед активацией

**Адаптеры:**
- `SequenceController` (adapter/in/) — REST контроллер с маппингом `/api/v1/sequences`
- `SequenceJpaAdapter` (adapter/out/) — JPA-реализация репозитория

**DTO (пакет dto/):**
- `SequenceCreateRequest` { name, description, startCriteriaJson, stopCriteriaJson }
- `SequenceUpdateRequest` { name, description, startCriteriaJson, stopCriteriaJson }
- `SequenceResponse` { id, name, description, status, startCriteriaJson, stopCriteriaJson, steps[], createdAt, updatedAt, createdBy }
- `StepCreateRequest` { name, stepType, configJson, timeoutSeconds, onSuccessAction, onSuccessGotoStep, onSuccessNotify, onFailureAction, onFailureGotoStep, onFailureNotify }
- `StepUpdateRequest` { то же что StepCreateRequest }
- `StepResponse` { id, orderIndex, name, stepType, configJson, timeoutSeconds, on*Action, on*GotoStep, on*Notify }
- `PageResponse<T>` { content[], totalElements, totalPages, currentPage, pageSize } — универсальный для пагинации

**REST API:**

| Метод | URL | Описание | UC | Пагинация |
|---|---|---|---|---|
| POST | /api/v1/sequences | Создать последовательность | UC-01 | — |
| GET | /api/v1/sequences | Список | UC-05 | page, size, status (фильтр) |
| GET | /api/v1/sequences/{id} | Получить с шагами | UC-05 | — |
| PUT | /api/v1/sequences/{id} | Обновить метаданные | UC-01 | — |
| DELETE | /api/v1/sequences/{id} | Удалить (только DRAFT) | UC-01 | — |
| POST | /api/v1/sequences/{id}/activate | Активировать | UC-04 | — |
| POST | /api/v1/sequences/{id}/deactivate | Деактивировать | UC-04 | — |
| POST | /api/v1/sequences/{id}/steps | Добавить шаг | UC-02 | — |
| PUT | /api/v1/sequences/{id}/steps/{stepId} | Обновить шаг (+ переходы) | UC-02, UC-03 | — |
| DELETE | /api/v1/sequences/{id}/steps/{stepId} | Удалить шаг | UC-02 | — |
| PUT | /api/v1/sequences/{id}/steps/reorder | Изменить порядок шагов | UC-02 | — |

**Бизнес-правила (реализуй в SequenceValidator):**
1. Редактировать шаги можно ТОЛЬКО у последовательности в статусе DRAFT
2. Деактивировать можно только ACTIVE последовательность → статус INACTIVE
3. Из INACTIVE можно снова активировать → ACTIVE (после повторной валидации)
4. При активации — валидация:
   - Минимум 1 шаг
   - Все GOTO указывают на существующие шаги (в пределах orderIndex)
   - Хотя бы 1 путь ведёт к END (чтобы последовательность могла завершиться)
   - WAIT-шаги имеют timeoutSeconds > 0
5. При активации — публикация `SequenceActivatedEvent` (sequenceId, startCriteria)
6. При деактивации — публикация `SequenceDeactivatedEvent` (sequenceId)
7. Удалять можно только последовательность в статусе DRAFT

**Валидация DTO:**
- name: @NotBlank, @Size(max=100)
- stepType: @NotNull
- configJson: @NotNull
- onSuccessAction, onFailureAction: @NotNull
- onSuccessGotoStep: обязателен если action = GOTO
- timeoutSeconds: обязателен и > 0 для stepType = WAIT

**Доменные события:**
- `SequenceActivatedEvent(Long sequenceId, String startCriteriaJson)`
- `SequenceDeactivatedEvent(Long sequenceId)`

**AuditLog:** записывать CREATE_SEQUENCE, UPDATE_SEQUENCE, DELETE_SEQUENCE, ACTIVATE_SEQUENCE, DEACTIVATE_SEQUENCE, ADD_STEP, UPDATE_STEP, DELETE_STEP

## Критерий завершения:
CRUD работает через Swagger UI, валидация проверена (невалидные запросы получают 400), unit-тесты на SequenceService покрывают все бизнес-правила.

**Коммит:** `"Step 3: Sequence Manager — CRUD, validation, activation events"`
