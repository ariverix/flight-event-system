/**
 * Форма шага последовательности v2 (P7-3).
 *
 * Полная реализация всех 3 типов шагов по спецификации SITA:
 *   ACTION  — raise/close condition (уровень алерта), send uplink/ground (шаблон + origin),
 *              wait {x}{sec/min/hour}
 *   EVALUATE — мгновенная проверка критерия (CriteriaBuilder)
 *   WAIT     — ожидание критерия (CriteriaBuilder + timeout + fromThisPointOnly)
 *
 * Решения (transitions): CONTINUE / GOTO step {n} / END / ABORT + checkbox Notify.
 * Клиентская валидация блокирует submit при невалидной конфигурации.
 *
 * i18n: все строки из useEditorI18n().
 */

import React, { useState, useEffect, useCallback } from 'react';
import {
  Form,
  Select,
  Input,
  InputNumber,
  Button,
  Space,
  Checkbox,
  Divider,
  Alert,
  Tag,
  Segmented,
} from 'antd';

import type { StepResponse, StepCreateRequest } from '../../types/sequence';
import type { AlertLevel, UplinkOrigin } from '../../types/criteria';
import { CriteriaBuilder } from './CriteriaBuilder';
import { useEditorI18n } from '../../i18n/useEditorI18n';
import { useTheme } from '../../context/ThemeContext';
import { validateActionConfig, validateEvaluateConfig, validateWaitConfig } from '../../utils/stepValidation';
import { templatesApi } from '../../api/templatesApi';
import type { ApiTemplateResponse } from '../../api/generated/schema';
import type { FormState } from './stepFormParsing';
import { INITIAL_STATE, parseInitialValues, buildConfigJson } from './stepFormParsing';

// ── Типы ─────────────────────────────────────────────────────────────────────

type StepType = 'ACTION' | 'EVALUATE' | 'WAIT';
type ActionType = 'RAISE_CONDITION' | 'CLOSE_CONDITION' | 'SEND_UPLINK' | 'SEND_GROUND' | 'WAIT_TIME';
type TransitionAction = 'CONTINUE' | 'GOTO' | 'END' | 'ABORT';

interface AvailableStepRef {
  id: number;
  orderIndex: number;
}

export interface StepFormV2Props {
  initialValues?: StepResponse | null;
  availableSteps?: AvailableStepRef[];
  onSubmit: (data: StepCreateRequest) => void;
  onCancel: () => void;
}

// Привязка последовательности к борту (tail number AN / flight id FI + flight data) не хранится
// отдельным полем шага — по SITA-модели она выражается через start/stop-критерии (CriteriaBuilder):
// конкретный борт определяется тем, чьё сообщение/рейс подошёл под критерий. Отдельного эндпоинта
// /api/v1/sequences/{id}/bindings нет и не вводился — решение зафиксировано в docs/PROGRESS.md
// (Фаза 5-6 прогона апгрейда).

// ── Валидация состояния формы ─────────────────────────────────────────────────

function validateFormState(state: FormState): string[] {
  const configJson = buildConfigJson(state);
  let config: unknown;
  try { config = JSON.parse(configJson); } catch { return ['errInvalidJson']; }

  const msgs: string[] = [];

  let result;
  switch (state.stepType) {
    case 'ACTION':   result = validateActionConfig(config);   break;
    case 'EVALUATE': result = validateEvaluateConfig(config); break;
    case 'WAIT': {
      result = validateWaitConfig(config);
      // timeoutSeconds живёт на верхнем уровне (не в configJson) — валидируем из state
      if (state.timeoutSeconds !== null && state.timeoutSeconds < 0) {
        msgs.push('errTimeoutSeconds');
      }
      break;
    }
    default:         result = { valid: false, errors: [{ messageKey: 'errStepType' }] };
  }

  if (!result.valid) {
    result.errors.forEach(e => msgs.push(e.messageKey));
  }

  // GOTO-проверка
  if (state.onSuccessAction === 'GOTO' && state.onSuccessGotoStep === null) {
    msgs.push('errGotoMissing');
  }
  if (state.onFailureAction === 'GOTO' && state.onFailureGotoStep === null) {
    msgs.push('errGotoMissing');
  }

  return [...new Set(msgs)]; // дедупликация
}

// ── Главный компонент ─────────────────────────────────────────────────────────

export const StepFormV2: React.FC<StepFormV2Props> = ({
  initialValues,
  availableSteps = [],
  onSubmit,
  onCancel,
}) => {
  const d = useEditorI18n();
  const { isDark } = useTheme();

  const [state, setState] = useState<FormState>(
    initialValues ? parseInitialValues(initialValues) : INITIAL_STATE,
  );
  const [submitAttempted, setSubmitAttempted] = useState(false);
  const [templates, setTemplates] = useState<ApiTemplateResponse[]>([]);
  const [templatesLoading, setTemplatesLoading] = useState(false);

  const bd  = isDark ? 'rgba(255,255,255,0.08)' : 'rgba(0,0,0,0.08)';
  const t2  = isDark ? 'rgba(255,255,255,0.55)' : 'rgba(0,0,0,0.50)';

  // Обновление состояния (частичное)
  const update = useCallback(<K extends keyof FormState>(key: K, value: FormState[K]) => {
    setState(prev => ({ ...prev, [key]: value }));
  }, []);

  // Загрузка шаблонов при переходе к SEND_UPLINK/SEND_GROUND
  useEffect(() => {
    const msgType = state.actionType === 'SEND_UPLINK' ? 'UPLINK'
      : state.actionType === 'SEND_GROUND' ? 'GROUND'
      : null;
    if (!msgType) return;

    setTemplatesLoading(true);
    templatesApi.list({ messageType: msgType, active: true, size: 200 })
      .then(page => setTemplates(page.content ?? []))
      .catch(() => setTemplates([]))
      .finally(() => setTemplatesLoading(false));
  }, [state.actionType]);

  // Валидационные ошибки (lazy — только после первого submit)
  const errorKeys = submitAttempted ? validateFormState(state) : [];

  const getErrMsg = (keys: string[]): string | null => {
    const first = keys.find(k => d.validationErrors[k]);
    return first ? (d.validationErrors[first] ?? null) : null;
  };

  const handleSubmit = () => {
    setSubmitAttempted(true);
    const errs = validateFormState(state);
    if (errs.length > 0) return;

    const configJson = buildConfigJson(state);
    const stepData: StepCreateRequest = {
      stepType: state.stepType,
      configJson,
      // timeoutSeconds — отдельная колонка БД, передаётся на верхнем уровне (НЕ в configJson)
      timeoutSeconds: state.stepType === 'WAIT' ? (state.timeoutSeconds ?? undefined) : undefined,
      onSuccessAction: state.onSuccessAction,
      onSuccessGotoStep: state.onSuccessAction === 'GOTO' ? (state.onSuccessGotoStep ?? undefined) : undefined,
      onSuccessNotify: state.onSuccessNotify,
      onFailureAction: state.onFailureAction,
      onFailureGotoStep: state.onFailureAction === 'GOTO' ? (state.onFailureGotoStep ?? undefined) : undefined,
      onFailureNotify: state.onFailureNotify,
    };
    onSubmit(stepData);
  };

  // ── Тип шага ───────────────────────────────────────────────────────────────

  const stepTypeOptions = [
    { value: 'ACTION',   label: d.stepTypeAction },
    { value: 'EVALUATE', label: d.stepTypeEvaluate },
    { value: 'WAIT',     label: d.stepTypeWait },
  ];

  // ── ACTION-типы ────────────────────────────────────────────────────────────

  const actionTypeOptions = [
    { value: 'RAISE_CONDITION', label: d.configLabels['RAISE_CONDITION'] },
    { value: 'CLOSE_CONDITION', label: d.configLabels['CLOSE_CONDITION'] },
    { value: 'SEND_UPLINK',     label: d.configLabels['SEND_UPLINK'] },
    { value: 'SEND_GROUND',     label: d.configLabels['SEND_GROUND'] },
    { value: 'WAIT_TIME',       label: d.configLabels['WAIT_TIME'] },
  ];

  const alertLevelOptions = Object.entries(d.alertLevels).map(([v, label]) => ({ value: v, label }));

  const originOptions = [
    { value: 'COMPUTER_GENERATED', label: d.originComputerGenerated },
    { value: 'EXTERNAL_USER',      label: d.originExternalUser },
  ];

  const durationUnitOptions = [
    { value: 'SEC',  label: d.durationUnitSec },
    { value: 'MIN',  label: d.durationUnitMin },
    { value: 'HOUR', label: d.durationUnitHour },
  ];

  const templateOptions = templates.map(t => ({
    value: t.name ?? '',
    label: (
      <span>
        {t.name}
        {t.origin && (
          <Tag style={{ marginLeft: 6, fontSize: 10 }}>
            {t.origin === 'COMPUTER_GENERATED' ? d.originTagAuto : d.originTagUser}
          </Tag>
        )}
      </span>
    ),
  }));

  // ── Рендер полей ACTION ────────────────────────────────────────────────────

  const renderActionFields = () => {
    switch (state.actionType) {
      case 'RAISE_CONDITION':
      case 'CLOSE_CONDITION':
        return (
          <Space direction="vertical" style={{ width: '100%' }} size={10}>
            <Form.Item label={d.conditionNameLabel}>
              <Input
                value={state.conditionName}
                placeholder={d.conditionNamePlaceholder}
                onChange={e => update('conditionName', e.target.value)}
              />
            </Form.Item>
            <Form.Item label={d.alertLevelLabel}>
              <Select
                value={state.alertLevel || undefined}
                options={alertLevelOptions}
                placeholder={d.alertLevelPick}
                allowClear={state.actionType === 'CLOSE_CONDITION'}
                style={{ width: '100%' }}
                onChange={(v: string) => update('alertLevel', (v as AlertLevel) ?? '')}
              />
            </Form.Item>
          </Space>
        );

      case 'SEND_UPLINK':
        return (
          <Space direction="vertical" style={{ width: '100%' }} size={10}>
            <Form.Item label={d.templateLabel}>
              <Select
                showSearch
                loading={templatesLoading}
                placeholder={templatesLoading ? d.templateLoading : d.templatePick}
                value={state.templateName || undefined}
                options={templateOptions}
                style={{ width: '100%' }}
                filterOption={(input, opt) =>
                  String(opt?.value ?? '').toLowerCase().includes(input.toLowerCase())
                }
                onChange={(v: string) => update('templateName', v ?? '')}
              />
            </Form.Item>
            <Form.Item label={d.uplinkOriginLabel}>
              <Select
                value={state.origin || undefined}
                options={originOptions}
                placeholder={d.actionTypePick}
                style={{ width: '100%' }}
                onChange={(v: string) => update('origin', (v as UplinkOrigin) ?? '')}
              />
            </Form.Item>
          </Space>
        );

      case 'SEND_GROUND':
        return (
          <Space direction="vertical" style={{ width: '100%' }} size={10}>
            <Form.Item label={d.templateLabel}>
              <Select
                showSearch
                loading={templatesLoading}
                placeholder={templatesLoading ? d.templateLoading : d.templatePick}
                value={state.templateName || undefined}
                options={templateOptions}
                style={{ width: '100%' }}
                filterOption={(input, opt) =>
                  String(opt?.value ?? '').toLowerCase().includes(input.toLowerCase())
                }
                onChange={(v: string) => update('templateName', v ?? '')}
              />
            </Form.Item>
            <Form.Item label={d.recipientsLabel} extra={d.recipientsHelp}>
              <Input
                value={state.recipients}
                placeholder={d.recipientsPlaceholder}
                onChange={e => update('recipients', e.target.value)}
              />
            </Form.Item>
          </Space>
        );

      case 'WAIT_TIME':
        return (
          <Form.Item label={d.durationLabel}>
            <Space.Compact style={{ width: '100%' }}>
              <InputNumber
                min={1}
                value={state.durationValue}
                style={{ width: '60%' }}
                onChange={v => update('durationValue', v)}
              />
              <Select
                value={state.durationUnit}
                options={durationUnitOptions}
                style={{ width: '40%' }}
                onChange={(v: string) => update('durationUnit', v as 'SEC' | 'MIN' | 'HOUR')}
              />
            </Space.Compact>
          </Form.Item>
        );

      default:
        return null;
    }
  };

  // ── Рендер полей EVALUATE / WAIT ───────────────────────────────────────────

  const renderCriteriaFields = () => (
    <Space direction="vertical" style={{ width: '100%' }} size={10}>
      <div>
        <div style={{ fontSize: 12, fontWeight: 500, marginBottom: 6 }}>
          {d.criteriaBuilderTitle}
        </div>
        <CriteriaBuilder
          value={state.criteriaJson}
          onChange={v => update('criteriaJson', v)}
        />
      </div>

      {state.stepType === 'WAIT' && (
        <>
          <Form.Item label={d.timeoutSecondsLabel}>
            <InputNumber
              min={0}
              value={state.timeoutSeconds}
              style={{ width: '100%' }}
              onChange={v => update('timeoutSeconds', v)}
            />
          </Form.Item>
          <Checkbox
            checked={state.fromThisPointOnly}
            onChange={e => update('fromThisPointOnly', e.target.checked)}
          >
            {d.fromThisPointOnly}
          </Checkbox>
        </>
      )}
    </Space>
  );

  // ── Ошибки после попытки сохранения ───────────────────────────────────────

  const firstErrorMsg = getErrMsg(errorKeys);

  return (
    <div style={{ padding: '4px 0' }}>
      {/* Тип шага */}
      <Form layout="vertical">
        <Form.Item label={<span style={{ fontWeight: 600 }}>{d.stepTypePick}</span>}>
          <Segmented
            value={state.stepType}
            options={stepTypeOptions}
            onChange={(v) => setState({ ...INITIAL_STATE, stepType: v as StepType })}
            block
          />
        </Form.Item>
      </Form>

      <Divider style={{ margin: '10px 0', borderColor: bd }} />

      {/* Специфичные поля */}
      <Form layout="vertical">
        {state.stepType === 'ACTION' && (
          <>
            <Form.Item label={d.actionTypeLabel}>
              <Select
                value={state.actionType || undefined}
                options={actionTypeOptions}
                placeholder={d.actionTypePick}
                style={{ width: '100%' }}
                onChange={(v: string) => setState({
                  ...state,
                  actionType: v as ActionType,
                  conditionName: '',
                  alertLevel: '',
                  templateName: '',
                  origin: '',
                  recipients: '',
                  durationValue: null,
                })}
              />
            </Form.Item>
            {state.actionType && renderActionFields()}
          </>
        )}

        {(state.stepType === 'EVALUATE' || state.stepType === 'WAIT') && renderCriteriaFields()}
      </Form>

      {/* Ошибка валидации */}
      {submitAttempted && firstErrorMsg && (
        <Alert
          type="error"
          message={firstErrorMsg}
          showIcon
          style={{ marginBottom: 12 }}
        />
      )}

      <Divider style={{ margin: '12px 0', borderColor: bd }}>
        <span style={{ fontSize: 11, color: t2 }}>{d.transitionsTitle}</span>
      </Divider>

      {/* Переходы */}
      <div style={{ display: 'flex', gap: 12 }}>
        <div style={{ flex: 1, minWidth: 0 }}>
          <div style={{ fontSize: 11, fontWeight: 700, color: '#22c55e', marginBottom: 6 }}>
            {d.onSuccessTitle}
          </div>
          <div style={{ marginBottom: 6 }}>
            <div style={{ fontSize: 11, marginBottom: 3 }}>{d.decisionActionLabel}</div>
            <Select
              size="small"
              value={state.onSuccessAction}
              style={{ width: '100%' }}
              options={[
                { value: 'CONTINUE', label: d.decisionContinue },
                { value: 'GOTO',     label: d.decisionGoto },
                { value: 'END',      label: d.decisionEnd },
                { value: 'ABORT',    label: d.decisionAbort },
              ]}
              onChange={(v: string) => setState({ ...state, onSuccessAction: v as TransitionAction, onSuccessGotoStep: null })}
            />
          </div>
          {state.onSuccessAction === 'GOTO' && (
            <div style={{ marginBottom: 6 }}>
              <div style={{ fontSize: 11, marginBottom: 3 }}>{d.decisionGotoStepLabel}</div>
              <Select
                size="small"
                value={state.onSuccessGotoStep}
                style={{ width: '100%' }}
                allowClear
                placeholder="#"
                onChange={(v: number | null) => update('onSuccessGotoStep', v)}
                options={availableSteps.map(s => ({
                  value: s.orderIndex,
                  label: `${d.stepLabel} #${s.orderIndex}`,
                }))}
              />
            </div>
          )}
          <Checkbox
            checked={state.onSuccessNotify}
            onChange={e => update('onSuccessNotify', e.target.checked)}
            style={{ fontSize: 11 }}
          >
            {d.notifyOnSuccess}
          </Checkbox>
        </div>

        <div style={{ width: 1, background: bd, flexShrink: 0 }} />

        <div style={{ flex: 1, minWidth: 0 }}>
          <div style={{ fontSize: 11, fontWeight: 700, color: '#ef4444', marginBottom: 6 }}>
            {d.onFailureTitle}
          </div>
          <div style={{ marginBottom: 6 }}>
            <div style={{ fontSize: 11, marginBottom: 3 }}>{d.decisionActionLabel}</div>
            <Select
              size="small"
              value={state.onFailureAction}
              style={{ width: '100%' }}
              options={[
                { value: 'CONTINUE', label: d.decisionContinue },
                { value: 'GOTO',     label: d.decisionGoto },
                { value: 'END',      label: d.decisionEnd },
                { value: 'ABORT',    label: d.decisionAbort },
              ]}
              onChange={(v: string) => setState({ ...state, onFailureAction: v as TransitionAction, onFailureGotoStep: null })}
            />
          </div>
          {state.onFailureAction === 'GOTO' && (
            <div style={{ marginBottom: 6 }}>
              <div style={{ fontSize: 11, marginBottom: 3 }}>{d.decisionGotoStepLabel}</div>
              <Select
                size="small"
                value={state.onFailureGotoStep}
                style={{ width: '100%' }}
                allowClear
                placeholder="#"
                onChange={(v: number | null) => update('onFailureGotoStep', v)}
                options={availableSteps.map(s => ({
                  value: s.orderIndex,
                  label: `${d.stepLabel} #${s.orderIndex}`,
                }))}
              />
            </div>
          )}
          <Checkbox
            checked={state.onFailureNotify}
            onChange={e => update('onFailureNotify', e.target.checked)}
            style={{ fontSize: 11 }}
          >
            {d.notifyOnFailure}
          </Checkbox>
        </div>
      </div>

      <Divider style={{ margin: '12px 0', borderColor: bd }} />

      {/* Кнопки */}
      <Space>
        <Button type="primary" onClick={handleSubmit}>
          {initialValues ? d.submitSaveStep : d.submitAddStep}
        </Button>
        <Button onClick={onCancel}>{d.cancel}</Button>
      </Space>
    </div>
  );
};
