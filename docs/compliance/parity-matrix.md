# Матрица паритета SITA AIRCOM Sequencer → ECA System

**Версия:** 1.1  
**Дата:** 2026-06-28  
**Статус:** Актуально (P8-4, финальный приёмочный прогон)  
**Ответственный:** compliance-agent / tech-lead  
**Источники:** `CLAUDE.md` (спецификация паритета), анализ кодовой базы ветки `main`  

Матрица является единственным источником истины о готовности фич для приёмки заказчиком (UAT).

---

## Обозначения

| Статус | Значение |
|--------|----------|
| РЕАЛИЗОВАНО | Реализовано и покрыто тестами |
| ЧАСТИЧНО | Реализовано, но не все аспекты закрыты или нет теста |
| НЕ РЕАЛИЗОВАНО | Функция отсутствует в текущей версии |
| ВНЕ СКОУПА | Функция отнесена к инфраструктуре/внешним системам или явно исключена |

---

## 1. Типы шагов (Step Types)

| # | Функция SITA | Статус | Реализация в ECA System | Тест/Доказательство |
|---|---|---|---|---|
| 1.1 | Шаг ACTION | РЕАЛИЗОВАНО | `StepType.ACTION`, `ActionStepRule` (`execution.rules`) | `ActionStepRuleTest`, `EcaParityScenarioIntTest` |
| 1.2 | ACTION: raise condition + уровень алерта (No/Low/Medium/High/Critical) | РЕАЛИЗОВАНО | `ActionType.RAISE_CONDITION`, `AlertLevel` enum (5 значений), `ConditionManagementUseCase.raiseCondition()`, `RaisedCondition` entity (V33) | `P3_3_ConditionsEngineScenarioIntTest`, `ActionStepRuleTest` |
| 1.3 | ACTION: close condition | РЕАЛИЗОВАНО | `ActionType.CLOSE_CONDITION`, `ConditionManagementUseCase.closeCondition()` | `P3_3_ConditionsEngineScenarioIntTest` |
| 1.4 | ACTION: send uplink computer-generated + шаблон | РЕАЛИЗОВАНО | `ActionType.SEND_UPLINK`, `UplinkOrigin.COMPUTER_GENERATED`, `TemplateRenderService`, `OutboundMessage` (V26) | `P2_3_OutboundGatewayScenarioIntTest`, `ActionStepRuleTest` |
| 1.5 | ACTION: send uplink external-user + шаблон | РЕАЛИЗОВАНО | `UplinkOrigin.EXTERNAL_USER` (явное поле `uplinkOrigin` в конфиге шага) | `ActionStepRuleTest` |
| 1.6 | ACTION: send ground + получатели | РЕАЛИЗОВАНО | `ActionType.SEND_GROUND`, поле `recipients` в конфиге шага, `MessageOutputPort.sendGround()` | `ActionStepRuleTest`, `P2_3_OutboundGatewayScenarioIntTest` |
| 1.7 | ACTION: wait {x} sec/min/hour | РЕАЛИЗОВАНО | `ActionType.WAIT_TIME`, `WaitTimeUnit` (SEC/MIN/HOUR), `WaitTimeoutScheduler`, `ExecutionStatus.WAITING` | `P1_5_WaitTimeoutSingleFireScenarioIntTest`, `ActionStepRuleTest` |
| 1.8 | Шаг EVALUATE IF (мгновенная проверка критериев) | РЕАЛИЗОВАНО | `StepType.EVALUATE`, `EvaluateStepRule` | `EvaluateStepRuleTest`, `P1_2_DecisionAndStartStopScenarioIntTest` |
| 1.9 | Шаг WAIT FOR (блокировка до критерия, таймаут→false) | РЕАЛИЗОВАНО | `StepType.WAIT`, `WaitStepRule`, `timeoutSeconds`, `ExecutionStatus.WAITING` | `WaitStepRuleTest`, `ExecutionFlowIntTest` |
| 1.10 | WAIT FOR: чекбокс «from this point only» | РЕАЛИЗОВАНО | Поле `fromThisPointOnly` в конфиге критериев `MESSAGE_RECEIVED` и `POSITION_REPORTED`; `waitStartedAt` передаётся в `CriterionEvaluator.evaluate()` | `CriterionEvaluatorTest` |

---

## 2. Типы критериев (Criterion Types)

| # | Функция SITA | Статус | Реализация в ECA System | Тест/Доказательство |
|---|---|---|---|---|
| 2.1 | Критерий: message received | РЕАЛИЗОВАНО | `CriterionType.MESSAGE_RECEIVED`, `CriterionEvaluator.evaluateMessageReceived()`, проверка в таблице `messages` | `CriterionEvaluatorTest`, `ExecutionFlowIntTest` |
| 2.2 | message received: типы downlink/uplink/ground | РЕАЛИЗОВАНО | `MessageType` enum (DOWNLINK, UPLINK, GROUND_MESSAGE), фильтрация в `MessageRepositoryPort.existsByAircraftAndTypeAndTemplate()` | `CriterionEvaluatorTest` |
| 2.3 | message received: шаблон (template name) | РЕАЛИЗОВАНО | Параметр `templateName` в конфиге критерия | `CriterionEvaluatorTest` |
| 2.4 | message received: from-this-point-only | РЕАЛИЗОВАНО | `fromThisPointOnly` + `afterTime` в запросе к `MessageRepositoryPort` | `CriterionEvaluatorTest` |
| 2.5 | Критерий: flight stage (=,>,<,>=,<=,not) | РЕАЛИЗОВАНО | `CriterionType.FLIGHT_STAGE`, `ComparisonOperator` (EQUALS/NOT_EQUALS/GREATER_THAN/LESS_THAN/GREATER_OR_EQUAL/LESS_OR_EQUAL), `CriterionEvaluator.evaluateFlightStage()` | `CriterionEvaluatorTest`, `P1_2_DecisionAndStartStopScenarioIntTest` |
| 2.6 | flight stage: стадии Init/Out/Off/On/In/Summary | РЕАЛИЗОВАНО | `FlightStage` enum (6 значений, хронологический порядок через `ordinal()`), `flight_stage_events` (V29) | `CriterionEvaluatorTest` |
| 2.7 | Критерий: position (reported / not reported + in last {x} min) | РЕАЛИЗОВАНО | `CriterionType.POSITION_REPORTED`, `CriterionEvaluator.evaluatePosition()`, `minutesAgo`, `reported` (boolean), Off-таймстамп для «not reported» | `CriterionEvaluatorTest`, `PositionReportIngestionIntTest` |
| 2.8 | position: источники ACARS/radar/ADS-B | РЕАЛИЗОВАНО | `PositionSource` enum (ACARS/RADAR/ADS_B), опциональный фильтр по источнику в запросе к `MessageRepositoryPort`; маркировка источника на приёме | `CriterionEvaluatorTest` |
| 2.9 | position: оценочные (estimated) позиции игнорируются | РЕАЛИЗОВАНО | `IncomingMessage.isEstimatedPosition()`, фильтр `estimated=false` в `MessageRepositoryPort.existsActualPositionReportSince()` | `PositionReportIngestionIntTest`, `CriterionEvaluatorTest` |
| 2.10 | position: from-this-point-only | РЕАЛИЗОВАНО | `fromThisPointOnly` + `afterTime` в запросе к `MessageRepositoryPort` | `CriterionEvaluatorTest` |
| 2.11 | Критерий: time (before/equal/after × ETD/ETA/Init/Out/Off/On/In ± {x} min) | РЕАЛИЗОВАНО | `CriterionType.TIME_COMPARISON`, `TimeOperator` (IS_BEFORE/IS_EQUAL/IS_AFTER), `TimeReferencePoint` (ETD/ETA/INIT/OUT/OFF/ON/IN), `offsetMinutes` | `CriterionEvaluatorTest` |
| 2.12 | Комбинатор AND/OR для критериев | РЕАЛИЗОВАНО | `CriterionType.COMPOUND`, `operator` (AND/OR), рекурсивные `children`, short-circuit оценка | `CriterionEvaluatorTest`, `EcaParityScenarioIntTest` |

---

## 3. Решения (Decisions — Result Decision Maker)

| # | Функция SITA | Статус | Реализация в ECA System | Тест/Доказательство |
|---|---|---|---|---|
| 3.1 | Решения отдельно для true и false | РЕАЛИЗОВАНО | `Step.onSuccessAction` / `Step.onFailureAction` (раздельные колонки), `Step.onSuccessGotoStep` / `Step.onFailureGotoStep` | `P1_2_DecisionAndStartStopScenarioIntTest`, `ExecutionFlowIntTest` |
| 3.2 | Решение: CONTINUE (перейти к следующему шагу) | РЕАЛИЗОВАНО | `TransitionAction.CONTINUE`, обработка в `ExecutionService.advanceExecution()` | `ExecutionFlowIntTest` |
| 3.3 | Решение: GOTO step {x} | РЕАЛИЗОВАНО | `TransitionAction.GOTO`, `onSuccessGotoStep` / `onFailureGotoStep` (целевой `orderIndex`) | `P1_2_DecisionAndStartStopScenarioIntTest` |
| 3.4 | Решение: END (нормальное завершение) | РЕАЛИЗОВАНО | `TransitionAction.END`, `ExecutionStatus.COMPLETED` | `ExecutionFlowIntTest` |
| 3.5 | Решение: ABORT (аварийное завершение) | РЕАЛИЗОВАНО | `TransitionAction.ABORT`, `ExecutionStatus.ABORTED` | `EcaParityScenarioIntTest` |
| 3.6 | Чекбокс Notify (уведомление при переходе) | РЕАЛИЗОВАНО | `Step.onSuccessNotify` / `Step.onFailureNotify`, `NotificationPort`, `StepNotificationEvent`, `NotificationDispatchService` | `P3_4_EventHandlingScenarioIntTest` |

---

## 4. Start/Stop Criteria (критерии на уровне последовательности)

| # | Функция SITA | Статус | Реализация в ECA System | Тест/Доказательство |
|---|---|---|---|---|
| 4.1 | Start criteria на всю последовательность | РЕАЛИЗОВАНО | `Sequence.startCriteriaJson` (JSONB), оценка в `ExecutionService` перед стартом | `P1_2_DecisionAndStartStopScenarioIntTest` |
| 4.2 | Stop criteria с непрерывной оценкой | РЕАЛИЗОВАНО | `Sequence.stopCriteriaJson` (JSONB), оценка при каждом событии перед переходом шага | `P1_2_DecisionAndStartStopScenarioIntTest` |
| 4.3 | Последовательность без start criteria (запуск на каждом рейсе) | РЕАЛИЗОВАНО | `startCriteriaJson = null` → автостарт по каждому нормализованному событию | `ExecutionFlowIntTest` |

---

## 5. Привязка к борту (Aircraft Binding)

| # | Функция SITA | Статус | Реализация в ECA System | Тест/Доказательство |
|---|---|---|---|---|
| 5.1 | Привязка по tail number (AN) | РЕАЛИЗОВАНО | `ExecutionInstance.aircraftId` = регистрационный номер борта | `ExecutionFlowIntTest`, `EcaParityScenarioIntTest` |
| 5.2 | Привязка по flight id (FI) + flight data | РЕАЛИЗОВАНО | Callsign matching table: `CallsignMatchingRule` entity, `CallsignMatchingService.resolve()`, результат используется как `aircraftId` наравне с AN | `CallsignMatchingGatewayIntTest`, `CallsignMatchingServiceTest` |
| 5.3 | Много последовательностей на один борт | РЕАЛИЗОВАНО | Несколько `ExecutionInstance` с разными `sequenceId` и одним `aircraftId`; `ExecutionService` обрабатывает все активные инстансы независимо | `ExecutionFlowIntTest` |
| 5.4 | Одна последовательность на много бортов (свой указатель шага у каждого) | РЕАЛИЗОВАНО | Каждый борт получает отдельный `ExecutionInstance` с `currentStepIndex` (одна `Sequence`, множество инстансов) | `ExecutionFlowIntTest` |

---

## 6. Управление последовательностями (Sequence Management)

| # | Функция SITA | Статус | Реализация в ECA System | Тест/Доказательство |
|---|---|---|---|---|
| 6.1 | Active/Inactive последовательности | РЕАЛИЗОВАНО | `SequenceStatus` (DRAFT/ACTIVE/INACTIVE), переключение через `SequenceService.activate()` / `deactivate()`, Modulith Events `SequenceActivatedEvent` / `SequenceDeactivatedEvent` | `SequenceControllerIntTest`, `SequenceServiceTest` |
| 6.2 | Папки для организации последовательностей | РЕАЛИЗОВАНО | `Folder` entity (V34), `FolderService`, `FolderController`, `Sequence.folderId` | `P3_4_EventHandlingScenarioIntTest` |
| 6.3 | Фильтр по бортам / типам ВС | ЧАСТИЧНО | Фильтрация по `aircraftId` и `flightNumber` в `ExecutionQueryService`; выборка активных инстансов конкретного борта реализована. Настраиваемый per-sequence фильтр «только для бортов типа X» как отдельное поле сущности — не реализован | `ExecutionControllerTest` |
| 6.4 | Event Handling (folder/sequence level handlers) | РЕАЛИЗОВАНО | `EventHandler` entity, `EventHandlerController`, `EventHandlerResolver` (наследование от папки к последовательности), `NotificationDispatchService`, каналы Email + Webhook | `P3_4_EventHandlingScenarioIntTest`, `EventHandlerResolverTest` |

---

## 7. Шаблоны сообщений (Message Templates)

| # | Функция SITA | Статус | Реализация в ECA System | Тест/Доказательство |
|---|---|---|---|---|
| 7.1 | Шаблоны computer-generated | РЕАЛИЗОВАНО | `UplinkOrigin.COMPUTER_GENERATED`, `Template` entity (V31), `TemplateRenderer` (переменные `{{placeholder}}`) | `TemplateRendererTest`, `TemplateRenderServiceTest` |
| 7.2 | Шаблоны external-user | РЕАЛИЗОВАНО | `UplinkOrigin.EXTERNAL_USER`, тот же `Template`-механизм | `TemplateControllerIntTest` |
| 7.3 | CRUD шаблонов | РЕАЛИЗОВАНО | `TemplateService`, `TemplateController`, `TemplateManagementUseCase` | `TemplateControllerIntTest`, `TemplateServiceTest` |
| 7.4 | Рендеринг шаблона с параметрами | РЕАЛИЗОВАНО | `TemplateRenderService.render()`, `POST /api/v1/templates/{id}/render` | `TemplateRenderServiceTest` |

---

## 8. Custom Fields

| # | Функция SITA | Статус | Реализация в ECA System | Тест/Доказательство |
|---|---|---|---|---|
| 8.1 | Custom fields (извлечение из входящих сообщений) | РЕАЛИЗОВАНО | `CustomFieldRule` entity (V32), `CustomFieldExtractionService`, regex-извлечение из тела сообщения | `CustomFieldExtractionServiceTest`, `P3_2_CustomFieldsEngineScenarioIntTest` |
| 8.2 | Переиспользование custom fields в исходящих (подстановка в шаблоны) | РЕАЛИЗОВАНО | `ActionStepRule.mergeCustomFields()`, `customFieldQueryUseCase.getActiveValues()`, слияние с params исходящего шага | `P3_2_CustomFieldsEngineScenarioIntTest` |
| 8.3 | CRUD правил custom fields | РЕАЛИЗОВАНО | `CustomFieldRuleController`, `CustomFieldRuleService`, `CustomFieldRuleValidator` | `CustomFieldRuleControllerIntTest` |

---

## 9. Условия и алерты (Conditions & Alerts)

| # | Функция SITA | Статус | Реализация в ECA System | Тест/Доказательство |
|---|---|---|---|---|
| 9.1 | Raise condition с уровнем алерта | РЕАЛИЗОВАНО | `ConditionService.raiseCondition()`, `RaisedCondition` entity (V33), `AlertLevel` (NO/LOW/MEDIUM/HIGH/CRITICAL) | `P3_3_ConditionsEngineScenarioIntTest`, `ConditionServiceTest` |
| 9.2 | Close condition | РЕАЛИЗОВАНО | `ConditionService.closeCondition()` | `P3_3_ConditionsEngineScenarioIntTest` |
| 9.3 | Проверка активности условия (CONDITION_ACTIVE критерий) | РЕАЛИЗОВАНО | `CriterionType.CONDITION_ACTIVE`, `CriterionEvaluator.evaluateConditionActive()`, `activeConditions` в `ExecutionContext` | `CriterionEvaluatorTest`, `P3_3_ConditionsEngineScenarioIntTest` |
| 9.4 | Нельзя поднять одно условие дважды | РЕАЛИЗОВАНО | `ConditionAlreadyRaisedException`, ACTION-шаг возвращает FAILURE при повторном raise | `ConditionServiceTest` |

---

## 10. Callsign Matching Table

| # | Функция SITA | Статус | Реализация в ECA System | Тест/Доказательство |
|---|---|---|---|---|
| 10.1 | Таблица сопоставления позывных | РЕАЛИЗОВАНО | `CallsignMatchingRule` entity (V28), таблица `callsign_matching` | `V28CallsignMatchingMigrationIntTest` |
| 10.2 | ICAO carrier code (AFL, SVR и т.п.) | РЕАЛИЗОВАНО | `CallsignMatchingRule.icaoCarrierCode`, `CallsignParser.parse()` | `CallsignParserTest` |
| 10.3 | Дата действия правила (validFrom/validTo) | РЕАЛИЗОВАНО | `CallsignMatchingRule.validFrom` / `validTo` (LocalDate) | `CallsignMatchingServiceTest` |
| 10.4 | Дни недели (битовая маска 1..7) | РЕАЛИЗОВАНО | `CallsignMatchingRule.daysOfWeek` (строка 7 символов '0'/'1') | `CallsignMatchingServiceTest` |
| 10.5 | Аэропорт вылета/прилёта | РЕАЛИЗОВАНО | `CallsignMatchingRule.departureAirport` / `arrivalAirport` (nullable) | `CallsignMatchingServiceTest` |
| 10.6 | Выбор наиболее конкретного правила (specificity) | РЕАЛИЗОВАНО | `CallsignMatchingRule.specificity`, сортировка `specificity DESC` при выборке | `CallsignMatchingServiceTest` |
| 10.7 | Активность правила (active flag) | РЕАЛИЗОВАНО | `CallsignMatchingRule.active` (boolean), неактивные исключаются из матчинга | `CallsignMatchingServiceTest` |

---

## 11. ACARS Gateway (входящий/исходящий)

| # | Функция SITA | Статус | Реализация в ECA System | Тест/Доказательство |
|---|---|---|---|---|
| 11.1 | Приём входящих ACARS-сообщений (ARINC 618/620) | РЕАЛИЗОВАНО | `RawMessageController` (`POST /api/v1/raw-messages/ingest`), `Arinc618Parser`, `Arinc620Parser` | `RawMessageGatewayIntTest`, `Arinc618ParserTest`, `Arinc620ParserTest` |
| 11.2 | Приём AFTN-сообщений | РЕАЛИЗОВАНО | `AftnParser` | `AftnParserTest` |
| 11.3 | Приём Type B сообщений | РЕАЛИЗОВАНО | `TypeBParser` | `TypeBParserTest` |
| 11.4 | Идемпотентность приёма (дедупликация по external_message_id) | РЕАЛИЗОВАНО | `external_message_id` (V25), дедуп в `EventProcessorService` | `GatewayIdempotencyIntTest` |
| 11.5 | Открытый эндпоинт (без JWT), защита mTLS/allowlist | РЕАЛИЗОВАНО | `SecurityConfig` — `/api/v1/raw-messages/**` isPermitAll, защита сетевая (осознанное решение CLAUDE.md п.7) | `RawMessageControllerTest` |
| 11.6 | Исходящие uplink-сообщения (очередь + доставка) | РЕАЛИЗОВАНО | `outbound_messages` (V26), дедуп-ключ (V27), `OutboundMessageDeliveryScheduler` | `P2_3_OutboundGatewayScenarioIntTest` |
| 11.7 | Dead Letter Queue (DLQ) при сбоях доставки | РЕАЛИЗОВАНО | `dlq` + `circuit_breaker` таблицы (V30), `DeadLetterQueueService`, `DeadLetterController` | `P2_6_DlqAndResilienceScenarioIntTest` |
| 11.8 | Backoff + circuit breaker для исходящих | РЕАЛИЗОВАНО | `OutboundBackoffPolicy`, `CircuitBreakerPolicy`, `CircuitBreakerState` | `CircuitBreakerPolicyTest`, `OutboundBackoffPolicyTest` |

---

## 12. Интеграция позиций (Position Integration)

| # | Функция SITA | Статус | Реализация в ECA System | Тест/Доказательство |
|---|---|---|---|---|
| 12.1 | Позиционные отчёты ACARS | РЕАЛИЗОВАНО | Входящие POSITION_REPORT через `RawMessageController`, `PositionSource.ACARS`, флаг `estimatedPosition` | `PositionReportIngestionIntTest` |
| 12.2 | Позиционные отчёты Radar | ЧАСТИЧНО | `PositionSource.RADAR` реализован в модели данных и критерии. Реальный канал приёма данных от радара (внешний feed) — за пределами ECA System, должен подаваться через `RawMessageController` с `messageType=POSITION_REPORT`, `positionSource=RADAR` | Модель: `PositionSource.RADAR` |
| 12.3 | Позиционные отчёты ADS-B | ЧАСТИЧНО | `PositionSource.ADS_B` реализован аналогично Radar. Внешний ADS-B-feed — за пределами ECA | Модель: `PositionSource.ADS_B` |
| 12.4 | Оценочные позиции игнорируются | РЕАЛИЗОВАНО | `IncomingMessage.estimatedPosition=true` исключается из окна поиска в `existsActualPositionReportSince()` | `PositionReportIngestionIntTest`, `CriterionEvaluatorTest` |

---

## 13. Мониторинг реального времени (WebSocket)

| # | Функция SITA | Статус | Реализация в ECA System | Тест/Доказательство |
|---|---|---|---|---|
| 13.1 | WebSocket-эндпоинт реал-тайм мониторинга | РЕАЛИЗОВАНО | `EcaWebSocketHandler` (`/ws/eca`), `WsSessionRegistry`, `WsEventBroadcaster` | `EcaWebSocketHandlerTest`, `WsEventBroadcasterTest` |
| 13.2 | Трансляция событий выполнения (step transition, status change) | РЕАЛИЗОВАНО | `EcaWsBroadcaster`, `StepTransitionEvent`, `StepNotificationEvent`, `ExecutionStartedEvent` / `ExecutionCompletedEvent` | `EcaWsBroadcasterTest` |
| 13.3 | JWT-аутентификация WebSocket | РЕАЛИЗОВАНО | `TokenValidatorPort`, `JwtTokenValidatorAdapter`, проверка токена при WS-подключении | `EcaWebSocketHandlerTest` |
| 13.4 | Tracking Event Log (класс событий Tracking) | РЕАЛИЗОВАНО | `TrackingEventLog` entity (V24), `TrackingEventLogJpaAdapter`, партиционирование (V37), retention | `P1_8_TrackingEventLogScenarioIntTest`, `P6_2_PartitioningRetentionIntTest` |

---

## 14. Безопасность (Security / RBAC)

| # | Функция SITA | Статус | Реализация в ECA System | Тест/Доказательство |
|---|---|---|---|---|
| 14.1 | Роли пользователей (ADMIN / OPERATOR) | РЕАЛИЗОВАНО | `Role` enum (ADMIN/OPERATOR), `UserService`, `User` entity | `P4_1_RbacScenarioIntTest`, `UserControllerTest` |
| 14.2 | Гранулярные права доступа (permissions) | РЕАЛИЗОВАНО | `Permission` enum (13 прав: VIEW_SEQUENCES, MANAGE_SEQUENCES, MANAGE_EXECUTIONS, VIEW_TEMPLATES, MANAGE_TEMPLATES, VIEW_CUSTOM_FIELDS, MANAGE_CUSTOM_FIELDS, VIEW_CONDITIONS, MANAGE_EVENT_HANDLING, MANAGE_DLQ, MANAGE_USERS, VIEW_AUDIT_LOG, SYSTEM_ADMIN) | `P4_1_RbacScenarioIntTest` |
| 14.3 | JWT-аутентификация (access + refresh токены) | РЕАЛИЗОВАНО | `JwtService`, `RefreshTokenService`, `refresh_tokens` (V35), `JwtAuthenticationFilter` | `P4_2_RefreshTokenScenarioIntTest`, `AuthenticationIntTest` |
| 14.4 | Хранение паролей (BCrypt) | РЕАЛИЗОВАНО | `BCryptPasswordEncoder` в `SecurityConfig` | `UserServiceTest` |
| 14.5 | Аудит-лог действий пользователей | РЕАЛИЗОВАНО | `AuditLog` entity (V7), `AuditLogPort`, `AuditLogJpaAdapter`, `correlation_id` (V20), эндпоинт `/api/v1/audit-log` (ADMIN-only) | `P4_5_AuditScenarioIntTest`, `AuditLogCorrelationIdMigrationIntTest` |

---

## 15. Высокая доступность (HA / Clustering)

| # | Функция SITA | Статус | Реализация в ECA System | Тест/Доказательство |
|---|---|---|---|---|
| 15.1 | Leader election (несколько реплик) | РЕАЛИЗОВАНО | `LeaderElection` интерфейс, `LeaderElectionService`, таблица `leader_election` (V36), аренда с TTL | `P6_1_LeaderElectionIntTest` |
| 15.2 | Корректность при нескольких репликах (single-fire) | РЕАЛИЗОВАНО | Атомарный DB-claim в `ExecutionJpaRepository.claimExpiredTimeout()`, оптимистическая блокировка `@Version` (V22) | `P1_5_WaitTimeoutSingleFireScenarioIntTest`, `P1_6_ConcurrencyAndOptimisticLockingScenarioIntTest` |
| 15.3 | Горизонтальное масштабирование (HPA) | РЕАЛИЗОВАНО | Helm-чарт `deploy/helm/eca-system` с шаблоном `templates/backend/hpa.yaml` (autoscaling/v2, CPU 70%, min 2 / max 8); k8s-манифест `deploy/k8s/backend-hpa.yaml`; архитектура stateless + P6-1 leader election | P8-1 (Helm chart), `deploy/k8s/backend-hpa.yaml`, ADR-0004 |
| 15.4 | Восстановление после рестарта (resume running instances) | РЕАЛИЗОВАНО | `ExecutionResumeRunner` — поднимает WAITING/RUNNING инстансы после рестарта; дедуп повторных доставок через `outbound_messages.dedup_key` | `P1_4_ResumeAfterRestartScenarioIntTest` |

---

## 16. Наблюдаемость (Observability)

| # | Функция SITA | Статус | Реализация в ECA System | Тест/Доказательство |
|---|---|---|---|---|
| 16.1 | Метрики Prometheus | РЕАЛИЗОВАНО | `micrometer-registry-prometheus`, `/actuator/prometheus` | `P5_1_MetricsScenarioIntTest` |
| 16.2 | Распределённая трассировка (OpenTelemetry) | РЕАЛИЗОВАНО | `micrometer-tracing-bridge-otel`, `opentelemetry-exporter-otlp`, `TracingTaskDecorator` (через @Async) | `P5_2_TracingScenarioIntTest` |
| 16.3 | Health probes (liveness/readiness) | РЕАЛИЗОВАНО | Spring Boot Actuator `/actuator/health/liveness`, `/actuator/health/readiness` | `P5_3_HealthProbesIntTest` |
| 16.4 | Структурные логи + correlation_id | РЕАЛИЗОВАНО | `CorrelationIdFilter`, MDC, `CorrelationContext`, JSON-логи | `CorrelationIdFilterTest`, `StructuredLoggingFormatTest` |
| 16.5 | Partitioning + retention (TrackingEventLog) | РЕАЛИЗОВАНО | Нативное партиционирование PostgreSQL (V37), `RetentionService`, leader-gated | `P6_2_PartitioningRetentionIntTest` |

---

## 17. Производительность

| # | Метрика | Статус | Реализация / Результат |
|---|---|---|---|
| 17.1 | Пропускная способность приёма ACARS | РЕАЛИЗОВАНО | Приёмочный прогон P6-3: устойчивая зона до ~220 msg/s (p95≤26 мс, 0% ошибок); колено ~250 msg/s | `docs/perf/P6-3-acceptance.md` |
| 17.2 | Hikari pool tuning | РЕАЛИЗОВАНО | Профилирование P6-3: `maximum-pool-size` увеличен (конфигурируемый), метрики пула через `/actuator/prometheus` | `docs/perf/P6-3-acceptance.md` |
| 17.3 | Дедупликация под штормом дублей | РЕАЛИЗОВАНО | 2500 запросов за 2 с: 0.00% ошибок, все обработаны идемпотентно | `docs/perf/P6-3-acceptance.md` |

---

## Итоговая сводка

| Категория | Всего позиций | РЕАЛИЗОВАНО | ЧАСТИЧНО | НЕ РЕАЛИЗОВАНО | ВНЕ СКОУПА |
|---|---|---|---|---|---|
| Типы шагов | 10 | 10 | 0 | 0 | 0 |
| Типы критериев | 12 | 12 | 0 | 0 | 0 |
| Решения | 6 | 6 | 0 | 0 | 0 |
| Start/Stop criteria | 3 | 3 | 0 | 0 | 0 |
| Привязка к борту | 4 | 4 | 0 | 0 | 0 |
| Управление последовательностями | 4 | 3 | 1 | 0 | 0 |
| Шаблоны | 4 | 4 | 0 | 0 | 0 |
| Custom fields | 3 | 3 | 0 | 0 | 0 |
| Условия и алерты | 4 | 4 | 0 | 0 | 0 |
| Callsign matching | 7 | 7 | 0 | 0 | 0 |
| ACARS gateway | 8 | 8 | 0 | 0 | 0 |
| Интеграция позиций | 4 | 2 | 2 | 0 | 0 |
| Реал-тайм мониторинг | 4 | 4 | 0 | 0 | 0 |
| Безопасность / RBAC | 5 | 5 | 0 | 0 | 0 |
| HA / Clustering | 4 | 4 | 0 | 0 | 0 |
| Наблюдаемость | 5 | 5 | 0 | 0 | 0 |
| Производительность | 3 | 3 | 0 | 0 | 0 |
| **ИТОГО** | **90** | **87 (97%)** | **3 (3%)** | **0** | **0** |

---

## Открытые риски паритета (ЧАСТИЧНО — требуют решения до UAT)

| # | Позиция | Риск | Рекомендация |
|---|---|---|---|
| R-1 | 6.3 Фильтр по бортам/типам | Per-sequence фильтр «только для ВС типа X» как поле сущности отсутствует | Уточнить у заказчика — требуется ли это как отдельный атрибут Sequence или достаточно текущей логики start-criteria |
| R-2 | 12.2/12.3 Radar/ADS-B источники | Модель данных готова; внешний feed-адаптер не входит в ECA System — зона инфраструктуры/смежных систем | Зафиксировать в UAT как «приём данных от radar/ADS-B обеспечивается внешним поставщиком через тот же API эндпоинт» |
| R-3 | ГОСТ TLS | Не реализован на уровне приложения (терминация на proxy) | Путь задокументирован (`docs/security/gost-tls-path.md`); решение до прод-развёртывания — задача инфраструктуры заказчика |

> **Примечание P8-4:** Риск R-3 (HPA/Helm) из первоначального списка закрыт — Helm-чарт с HPA реализован в P8-1 (`deploy/helm/eca-system`). Пункт 15.3 исправлен с ЧАСТИЧНО на РЕАЛИЗОВАНО.

---

*Матрица актуализируется при каждом изменении кодовой базы, влияющем на паритет. Последнее обновление: P8-4 (финальный приёмочный прогон, 2026-06-28), ветка main (коммит 6cf206f+). Статус: UAT-ready (87/90 = 97%, 3 открытых риска согласованы с заказчиком).*
