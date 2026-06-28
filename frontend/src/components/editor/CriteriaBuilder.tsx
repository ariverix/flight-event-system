/**
 * Конструктор критериев (P7-3).
 *
 * Визуальный редактор дерева критериев вместо временного JSON-редактора.
 * Поддерживает все 6 типов критериев + рекурсивную вложенность AND/OR.
 *
 * Интерфейс совместим с CriteriaEditor: принимает/возвращает JSON-строку.
 *
 * Использование:
 *   <CriteriaBuilder value={jsonStr} onChange={setJsonStr} />
 */

import React, { useMemo, useCallback } from 'react';
import {
  Button,
  Select,
  Input,
  InputNumber,
  Checkbox,
  Segmented,
  Alert,
  Space,
  Tag,
  Divider,
} from 'antd';
import { PlusOutlined, DeleteOutlined } from '@ant-design/icons';

import type {
  CriteriaNode,
  CriterionType,
  CompoundLogic,
  MessageDirection,
  FlightStageOperator,
  FlightStageValue,
  PositionSource,
  TimeOperator,
  TimeReference,
} from '../../types/criteria';
import { useEditorI18n } from '../../i18n/useEditorI18n';
import { useTheme } from '../../context/ThemeContext';

// ── Константы ─────────────────────────────────────────────────────────────────

const CRITERION_TYPES: CriterionType[] = [
  'MESSAGE_RECEIVED',
  'FLIGHT_STAGE',
  'POSITION_REPORTED',
  'TIME_COMPARISON',
  'CONDITION_ACTIVE',
  'COMPOUND',
];

const TYPE_ACCENT: Record<string, string> = {
  MESSAGE_RECEIVED:  '#1677ff',
  FLIGHT_STAGE:      '#d48806',
  POSITION_REPORTED: '#22c55e',
  TIME_COMPARISON:   '#7c3aed',
  CONDITION_ACTIVE:  '#ef4444',
  COMPOUND:          '#64748b',
};

// ── Дефолтные значения при создании нового критерия ──────────────────────────

function defaultCriterion(type: CriterionType): CriteriaNode {
  switch (type) {
    case 'MESSAGE_RECEIVED':
      return { type: 'MESSAGE_RECEIVED', messageType: 'DOWNLINK' };
    case 'FLIGHT_STAGE':
      return { type: 'FLIGHT_STAGE', operator: 'EQUALS', targetStage: 'OUT' };
    case 'POSITION_REPORTED':
      return { type: 'POSITION_REPORTED', reported: true };
    case 'TIME_COMPARISON':
      return { type: 'TIME_COMPARISON', operator: 'BEFORE', referenceTime: 'ETD' };
    case 'CONDITION_ACTIVE':
      return { type: 'CONDITION_ACTIVE', conditionName: '' };
    case 'COMPOUND':
      return {
        type: 'COMPOUND',
        logic: 'AND',
        criteria: [defaultCriterion('FLIGHT_STAGE')],
      };
  }
}

// ── Хелперы для иммутабельного обновления ────────────────────────────────────

function updateCriteriaAt(
  node: CriteriaNode,
  index: number,
  updater: (old: CriteriaNode) => CriteriaNode,
): CriteriaNode {
  if (node.type !== 'COMPOUND') return node;
  const newCriteria = [...node.criteria];
  newCriteria[index] = updater(newCriteria[index]);
  return { ...node, criteria: newCriteria };
}

function removeCriteriaAt(node: CriteriaNode, index: number): CriteriaNode {
  if (node.type !== 'COMPOUND') return node;
  const newCriteria = node.criteria.filter((_, i) => i !== index);
  return { ...node, criteria: newCriteria };
}

function addCriteria(node: CriteriaNode, child: CriteriaNode): CriteriaNode {
  if (node.type !== 'COMPOUND') return node;
  return { ...node, criteria: [...node.criteria, child] };
}

// ── Форма критерия MESSAGE_RECEIVED ──────────────────────────────────────────

interface MessageReceivedFormProps {
  node: Extract<CriteriaNode, { type: 'MESSAGE_RECEIVED' }>;
  onChange: (n: CriteriaNode) => void;
}

const MessageReceivedForm: React.FC<MessageReceivedFormProps> = ({ node, onChange }) => {
  const d = useEditorI18n();
  const dirOptions = Object.entries(d.msgDirections).map(([v, label]) => ({ value: v, label }));
  return (
    <Space direction="vertical" style={{ width: '100%' }} size={6}>
      <div>
        <div style={{ fontSize: 11, marginBottom: 3 }}>{d.msgDirectionLabel}</div>
        <Select
          value={node.messageType}
          options={dirOptions}
          style={{ width: '100%' }}
          onChange={(v: string) => onChange({ ...node, messageType: v as MessageDirection })}
        />
      </div>
      <div>
        <div style={{ fontSize: 11, marginBottom: 3 }}>{d.msgTemplateNameLabel}</div>
        <Input
          value={node.templateName ?? ''}
          placeholder={d.msgTemplateNamePh}
          onChange={e => onChange({ ...node, templateName: e.target.value || undefined })}
        />
      </div>
      <Checkbox
        checked={node.fromThisPointOnly ?? false}
        onChange={e => onChange({ ...node, fromThisPointOnly: e.target.checked || undefined })}
      >
        {d.msgFromThisPointLabel}
      </Checkbox>
    </Space>
  );
};

// ── Форма критерия FLIGHT_STAGE ───────────────────────────────────────────────

interface FlightStageFormProps {
  node: Extract<CriteriaNode, { type: 'FLIGHT_STAGE' }>;
  onChange: (n: CriteriaNode) => void;
}

const FlightStageForm: React.FC<FlightStageFormProps> = ({ node, onChange }) => {
  const d = useEditorI18n();
  const opOptions = Object.entries(d.stageOperators).map(([v, label]) => ({ value: v, label }));
  const stageOptions = Object.entries(d.flightStages).map(([v, label]) => ({ value: v, label }));
  return (
    <Space direction="vertical" style={{ width: '100%' }} size={6}>
      <div>
        <div style={{ fontSize: 11, marginBottom: 3 }}>{d.stageOperatorLabel}</div>
        <Select
          value={node.operator}
          options={opOptions}
          style={{ width: '100%' }}
          onChange={(v: string) => onChange({ ...node, operator: v as FlightStageOperator })}
        />
      </div>
      <div>
        <div style={{ fontSize: 11, marginBottom: 3 }}>{d.targetStageLabel}</div>
        <Select
          value={node.targetStage}
          options={stageOptions}
          style={{ width: '100%' }}
          onChange={(v: string) => onChange({ ...node, targetStage: v as FlightStageValue })}
        />
      </div>
    </Space>
  );
};

// ── Форма критерия POSITION_REPORTED ─────────────────────────────────────────

interface PositionFormProps {
  node: Extract<CriteriaNode, { type: 'POSITION_REPORTED' }>;
  onChange: (n: CriteriaNode) => void;
}

const PositionReportedForm: React.FC<PositionFormProps> = ({ node, onChange }) => {
  const d = useEditorI18n();
  const sourceOptions = Object.entries(d.positionSources).map(([v, label]) => ({ value: v, label }));
  return (
    <Space direction="vertical" style={{ width: '100%' }} size={6}>
      <div>
        <div style={{ fontSize: 11, marginBottom: 3 }}>{d.posStatusLabel}</div>
        <Segmented
          value={node.reported ? 'yes' : 'no'}
          options={[
            { value: 'yes', label: d.posReported },
            { value: 'no',  label: d.posNotReported },
          ]}
          onChange={(v) => onChange({ ...node, reported: v === 'yes' })}
        />
      </div>
      <div>
        <div style={{ fontSize: 11, marginBottom: 3 }}>{d.posInLastMinLabel}</div>
        <InputNumber
          value={node.inLastMinutes}
          min={1}
          placeholder={d.posInLastMinPh}
          style={{ width: '100%' }}
          onChange={(v) => onChange({ ...node, inLastMinutes: v ?? undefined })}
        />
      </div>
      <div>
        <div style={{ fontSize: 11, marginBottom: 3 }}>{d.posSourcesLabel}</div>
        <Select
          mode="multiple"
          value={node.sources ?? []}
          options={sourceOptions}
          style={{ width: '100%' }}
          onChange={(v: string[]) => onChange({ ...node, sources: v.length > 0 ? v as PositionSource[] : undefined })}
        />
      </div>
    </Space>
  );
};

// ── Форма критерия TIME_COMPARISON ────────────────────────────────────────────

interface TimeFormProps {
  node: Extract<CriteriaNode, { type: 'TIME_COMPARISON' }>;
  onChange: (n: CriteriaNode) => void;
}

const TimeComparisonForm: React.FC<TimeFormProps> = ({ node, onChange }) => {
  const d = useEditorI18n();
  const opOptions = Object.entries(d.timeOperators).map(([v, label]) => ({ value: v, label }));
  const refOptions = Object.entries(d.timeReferences).map(([v, label]) => ({ value: v, label }));
  return (
    <Space direction="vertical" style={{ width: '100%' }} size={6}>
      <div>
        <div style={{ fontSize: 11, marginBottom: 3 }}>{d.timeOperatorLabel}</div>
        <Select
          value={node.operator}
          options={opOptions}
          style={{ width: '100%' }}
          onChange={(v: string) => onChange({ ...node, operator: v as TimeOperator })}
        />
      </div>
      <div>
        <div style={{ fontSize: 11, marginBottom: 3 }}>{d.timeRefLabel}</div>
        <Select
          value={node.referenceTime}
          options={refOptions}
          style={{ width: '100%' }}
          onChange={(v: string) => onChange({ ...node, referenceTime: v as TimeReference })}
        />
      </div>
      <div>
        <div style={{ fontSize: 11, marginBottom: 3 }}>{d.timeOffsetLabel}</div>
        <InputNumber
          value={node.offsetMinutes}
          style={{ width: '100%' }}
          onChange={(v) => onChange({ ...node, offsetMinutes: v ?? undefined })}
        />
      </div>
    </Space>
  );
};

// ── Форма критерия CONDITION_ACTIVE ───────────────────────────────────────────

interface ConditionActiveFormProps {
  node: Extract<CriteriaNode, { type: 'CONDITION_ACTIVE' }>;
  onChange: (n: CriteriaNode) => void;
}

const ConditionActiveForm: React.FC<ConditionActiveFormProps> = ({ node, onChange }) => {
  const d = useEditorI18n();
  return (
    <div>
      <div style={{ fontSize: 11, marginBottom: 3 }}>{d.conditionNameLabel}</div>
      <Input
        value={node.conditionName}
        placeholder={d.conditionNamePlaceholder}
        onChange={e => onChange({ ...node, conditionName: e.target.value })}
      />
    </div>
  );
};

// ── Рекурсивный редактор узла критерия ───────────────────────────────────────

interface CriteriaNodeEditorProps {
  node: CriteriaNode;
  onChange: (n: CriteriaNode) => void;
  onRemove?: () => void;
  depth: number;
  isDark: boolean;
}

const CriteriaNodeEditor: React.FC<CriteriaNodeEditorProps> = ({
  node,
  onChange,
  onRemove,
  depth,
  isDark,
}) => {
  const d = useEditorI18n();

  const accent = TYPE_ACCENT[node.type] ?? '#888';
  const bg     = isDark ? 'rgba(255,255,255,0.03)' : 'rgba(0,0,0,0.02)';
  const bd     = isDark ? 'rgba(255,255,255,0.10)' : 'rgba(0,0,0,0.10)';
  const t2     = isDark ? 'rgba(255,255,255,0.55)' : 'rgba(0,0,0,0.50)';

  const typeOptions = CRITERION_TYPES.map(t => ({
    value: t,
    label: d.criterionLabels[t] ?? t,
  }));

  const handleTypeChange = (newType: string) => {
    onChange(defaultCriterion(newType as CriterionType));
  };

  // Для COMPOUND — отдельный блок
  if (node.type === 'COMPOUND') {
    return (
      <div style={{
        border: `1px solid ${accent}55`,
        borderRadius: 8,
        padding: 10,
        background: depth === 0
          ? (isDark ? 'rgba(100,116,139,0.06)' : 'rgba(100,116,139,0.04)')
          : bg,
        marginBottom: depth > 0 ? 6 : 0,
      }}>
        {/* Шапка группы */}
        <div style={{ display: 'flex', alignItems: 'center', gap: 6, marginBottom: 8 }}>
          <Tag style={{ color: accent, borderColor: `${accent}44`, background: `${accent}14`, margin: 0, fontSize: 10 }}>
            {d.groupTagLabel}
          </Tag>
          <Segmented
            size="small"
            value={node.logic}
            options={[
              { value: 'AND', label: d.logicAnd },
              { value: 'OR',  label: d.logicOr },
            ]}
            onChange={(v) => onChange({ ...node, logic: v as CompoundLogic })}
          />
          {onRemove && (
            <Button
              type="text"
              size="small"
              danger
              icon={<DeleteOutlined />}
              onClick={onRemove}
              style={{ marginLeft: 'auto', fontSize: 11 }}
            >
              {d.removeCriterion}
            </Button>
          )}
        </div>

        <Divider style={{ margin: '6px 0', borderColor: bd }} />

        {/* Вложенные критерии */}
        {node.criteria.map((child, i) => (
          <CriteriaNodeEditor
            key={i}
            node={child}
            onChange={(updated) => onChange(updateCriteriaAt(node, i, () => updated))}
            onRemove={() => onChange(removeCriteriaAt(node, i))}
            depth={depth + 1}
            isDark={isDark}
          />
        ))}

        {/* Кнопки добавления */}
        <Space size={4} style={{ marginTop: 6 }}>
          <Button
            type="dashed"
            size="small"
            icon={<PlusOutlined />}
            onClick={() => onChange(addCriteria(node, defaultCriterion('FLIGHT_STAGE')))}
            style={{ fontSize: 11 }}
          >
            {d.addCriterion}
          </Button>
          <Button
            type="dashed"
            size="small"
            icon={<PlusOutlined />}
            onClick={() => onChange(addCriteria(node, defaultCriterion('COMPOUND')))}
            style={{ fontSize: 11 }}
          >
            {d.addGroup}
          </Button>
        </Space>
      </div>
    );
  }

  // Обычный (листовой) критерий
  return (
    <div style={{
      border: `1px solid ${bd}`,
      borderLeft: `3px solid ${accent}`,
      borderRadius: 6,
      padding: '8px 10px',
      background: bg,
      marginBottom: 6,
    }}>
      {/* Тип критерия + кнопка удаления */}
      <div style={{ display: 'flex', alignItems: 'center', gap: 6, marginBottom: 6 }}>
        <span style={{ fontSize: 11, color: t2, flexShrink: 0 }}>{d.criterionTypeLabel}</span>
        <Select
          size="small"
          value={node.type}
          options={typeOptions}
          style={{ flex: 1 }}
          onChange={handleTypeChange}
          placeholder={d.criterionTypePick}
        />
        {onRemove && (
          <Button
            type="text"
            size="small"
            danger
            icon={<DeleteOutlined />}
            onClick={onRemove}
            style={{ padding: '0 4px', height: 22, flexShrink: 0 }}
          />
        )}
      </div>

      {/* Поля специфичные для типа */}
      {node.type === 'MESSAGE_RECEIVED' && (
        <MessageReceivedForm node={node} onChange={onChange} />
      )}
      {node.type === 'FLIGHT_STAGE' && (
        <FlightStageForm node={node} onChange={onChange} />
      )}
      {node.type === 'POSITION_REPORTED' && (
        <PositionReportedForm node={node} onChange={onChange} />
      )}
      {node.type === 'TIME_COMPARISON' && (
        <TimeComparisonForm node={node} onChange={onChange} />
      )}
      {node.type === 'CONDITION_ACTIVE' && (
        <ConditionActiveForm node={node} onChange={onChange} />
      )}
    </div>
  );
};

// ── Главный компонент ─────────────────────────────────────────────────────────

export interface CriteriaBuilderProps {
  /** JSON-строка критерия (совместимо с CriteriaEditor) */
  value?: string;
  onChange?: (value: string) => void;
  /** Если true — только просмотр, без редактирования */
  readOnly?: boolean;
}

export const CriteriaBuilder: React.FC<CriteriaBuilderProps> = ({
  value,
  onChange,
  readOnly = false,
}) => {
  const d = useEditorI18n();
  const { isDark } = useTheme();

  const t3 = isDark ? 'rgba(255,255,255,0.28)' : 'rgba(0,0,0,0.28)';
  const bd = isDark ? 'rgba(255,255,255,0.08)' : 'rgba(0,0,0,0.08)';

  // Парсим JSON → CriteriaNode | null
  const parsed = useMemo((): CriteriaNode | null => {
    if (!value?.trim()) return null;
    try {
      return JSON.parse(value) as CriteriaNode;
    } catch {
      return null;
    }
  }, [value]);

  const parseError = useMemo((): boolean => {
    if (!value?.trim()) return false;
    try { JSON.parse(value); return false; } catch { return true; }
  }, [value]);

  const handleChange = useCallback((node: CriteriaNode | null) => {
    if (!onChange) return;
    onChange(node ? JSON.stringify(node) : '');
  }, [onChange]);

  if (readOnly && !parsed) {
    return (
      <div style={{ fontSize: 12, color: t3, padding: '8px 0' }}>
        {d.criteriaNotSet}
      </div>
    );
  }

  return (
    <div>
      {parseError && (
        <Alert
          type="error"
          message={d.validationErrors.errInvalidJson}
          style={{ marginBottom: 8 }}
          showIcon
        />
      )}

      {parsed ? (
        <CriteriaNodeEditor
          node={parsed}
          onChange={readOnly ? () => {} : handleChange}
          depth={0}
          isDark={isDark}
        />
      ) : (
        <div style={{
          border: `1px dashed ${bd}`,
          borderRadius: 8,
          padding: '16px 12px',
          textAlign: 'center',
          color: t3,
          fontSize: 12,
        }}>
          {d.noCriteria}
        </div>
      )}

      {!readOnly && !parsed && (
        <Space style={{ marginTop: 8 }} size={6}>
          <Button
            type="dashed"
            size="small"
            icon={<PlusOutlined />}
            onClick={() => handleChange(defaultCriterion('FLIGHT_STAGE'))}
            style={{ fontSize: 11 }}
          >
            {d.addCriterion}
          </Button>
          <Button
            type="dashed"
            size="small"
            icon={<PlusOutlined />}
            onClick={() => handleChange(defaultCriterion('COMPOUND'))}
            style={{ fontSize: 11 }}
          >
            {d.addGroup}
          </Button>
        </Space>
      )}

      {!readOnly && parsed && (
        <Button
          type="link"
          size="small"
          danger
          style={{ marginTop: 4, fontSize: 11, padding: 0 }}
          onClick={() => handleChange(null)}
        >
          {d.removeCriterion}
        </Button>
      )}
    </div>
  );
};
