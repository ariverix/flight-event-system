/**
 * Удобные псевдонимы над сгенерированными типами OpenAPI.
 *
 * ИСТОЧНИК ИСТИНЫ: docs/openapi/openapi.json (сгенерирован springdoc).
 * Файл openapi.ts пересоздаётся командой `npm run gen:api` — не редактировать руками.
 *
 * Использование в новых API-модулях:
 *   import type { ApiSequenceResponse } from './generated/schema';
 *
 * Ручные типы в src/types/ вытесняются постепенно (план — ADR-0005, п. 3).
 */
import type { components } from './openapi';

type Schemas = components['schemas'];

// ── Sequences ─────────────────────────────────────────────────────────────────
export type ApiSequenceResponse       = Schemas['SequenceResponse'];
export type ApiSequenceCreateRequest  = Schemas['SequenceCreateRequest'];
export type ApiSequenceUpdateRequest  = Schemas['SequenceUpdateRequest'];
export type ApiStepResponse           = Schemas['StepResponse'];
export type ApiStepCreateRequest      = Schemas['StepCreateRequest'];
export type ApiStepUpdateRequest      = Schemas['StepUpdateRequest'];

// ── Executions ────────────────────────────────────────────────────────────────
export type ApiExecutionInstanceResponse = Schemas['ExecutionInstanceResponse'];
export type ApiStepExecutionResponse     = Schemas['StepExecutionResponse'];

// ── Templates ─────────────────────────────────────────────────────────────────
export type ApiTemplateResponse       = Schemas['TemplateResponse'];
export type ApiTemplateCreateRequest  = Schemas['TemplateCreateRequest'];
export type ApiTemplateUpdateRequest  = Schemas['TemplateUpdateRequest'];
export type ApiTemplateRenderRequest  = Schemas['TemplateRenderRequest'];
export type ApiTemplateRenderResponse = Schemas['TemplateRenderResponse'];

// ── Messages ──────────────────────────────────────────────────────────────────
export type ApiMessageResponse            = Schemas['MessageResponse'];
export type ApiIncomingMessageRequest     = Schemas['IncomingMessageRequest'];
export type ApiRawIncomingMessageRequest  = Schemas['RawIncomingMessageRequest'];
export type ApiMessageReceivedResponse    = Schemas['MessageReceivedResponse'];

// ── Aircraft (Фаза 5/6: список бортов для привязки последовательностей) ──────────
export type ApiAircraftSummaryResponse     = Schemas['AircraftSummaryResponse'];
export type ApiPageAircraftSummaryResponse = Schemas['PageAircraftSummaryResponse'];

// ── Users / Auth ──────────────────────────────────────────────────────────────
export type ApiUserResponse    = Schemas['UserResponse'];
export type ApiLoginRequest    = Schemas['LoginRequest'];
export type ApiRegisterRequest = Schemas['RegisterRequest'];
export type ApiRefreshRequest  = Schemas['RefreshRequest'];

// ── DLQ ───────────────────────────────────────────────────────────────────────
export type ApiDeadLetterMessageResponse  = Schemas['DeadLetterMessageResponse'];
export type ApiDeadLetterReprocessResponse = Schemas['DeadLetterReprocessResponse'];

// ── Conditions ────────────────────────────────────────────────────────────────
export type ApiRaisedConditionResponse = Schemas['RaisedConditionResponse'];

// ── Custom Field Rules ────────────────────────────────────────────────────────
export type ApiCustomFieldRuleResponse      = Schemas['CustomFieldRuleResponse'];
export type ApiCustomFieldRuleCreateRequest = Schemas['CustomFieldRuleCreateRequest'];
export type ApiCustomFieldRuleUpdateRequest = Schemas['CustomFieldRuleUpdateRequest'];

// ── Folders / Event Handlers ──────────────────────────────────────────────────
export type ApiFolderResponse           = Schemas['FolderResponse'];
export type ApiFolderCreateRequest      = Schemas['FolderCreateRequest'];
export type ApiEventHandlerResponse     = Schemas['EventHandlerResponse'];
export type ApiEventHandlerCreateRequest = Schemas['EventHandlerCreateRequest'];

// ── Audit Log ─────────────────────────────────────────────────────────────────
export type ApiAuditLogResponse = Schemas['AuditLogResponse'];

// ── Pagination ────────────────────────────────────────────────────────────────
/**
 * Универсальная страница. Используется вместо ручного PageResponse<T> в types/sequence.ts.
 * Поля помечены optional — OpenAPI генерирует их так из Spring Page.
 */
export interface ApiPage<T> {
  content?: T[];
  totalElements?: number;
  totalPages?: number;
  number?: number;
  size?: number;
}
