/**
 * Панель старт/стоп-критериев редактора последовательности (P7-2).
 *
 * Отображает текущие критерии старта и остановки:
 *  - визуальный статус «задан» / «не задан»
 *  - сводка (тип критерия из JSON)
 *  - кнопка «Изменить» открывает модальный JSON-редактор
 *
 * Детальный конструктор критериев (6 типов, AND/OR, операторы) — P7-3.
 * Здесь — каркас с отображением и точкой входа.
 */

import React, { useState } from 'react';
import { Modal, Button, Tag, Tooltip } from 'antd';
import {
  CheckCircleOutlined,
  ExclamationCircleOutlined,
  InfoCircleOutlined,
  EditOutlined,
} from '@ant-design/icons';
import { CriteriaBuilder } from './CriteriaBuilder';
import { useEditorI18n } from '../../i18n/useEditorI18n';
import { useTheme } from '../../context/ThemeContext';

// ── Типы ─────────────────────────────────────────────────────────────────────

interface StartStopPanelProps {
  startCriteriaJson: string | null;
  stopCriteriaJson: string | null;
  readOnly?: boolean;
  onSave: (startJson: string | null, stopJson: string | null) => void;
}

// ── Вспомогательные утилиты ──────────────────────────────────────────────────

// Метки типов критериев берутся из d.criterionLabels (dict.ts)

function getCriteriaSummary(
  json: string | null,
  labels: Record<string, string>,
  fallbackLabel: string,
  invalidJsonLabel: string,
): string | null {
  if (!json?.trim()) return null;
  try {
    const parsed: unknown = JSON.parse(json);
    if (parsed === null || typeof parsed !== 'object') return null;
    const obj = parsed as Record<string, unknown>;
    const type = (obj.type ?? obj.criterionType) as string | undefined;
    const label = type ? (labels[type] ?? type) : null;

    // Дополнение к метке
    const stage = obj.targetStage as string | undefined;
    const template = obj.templateName as string | undefined;

    if (label && stage) return `${label}: ${stage}`;
    if (label && template) return `${label}: ${template}`;
    if (label) return label;
    return fallbackLabel;
  } catch {
    return invalidJsonLabel;
  }
}

// ── Строка критерия ───────────────────────────────────────────────────────────

interface CriteriaRowProps {
  label: string;
  criteriaJson: string | null;
  accentColor: string;
  readOnly: boolean;
  onEdit: () => void;
  isDark: boolean;
}

const CriteriaRow: React.FC<CriteriaRowProps> = ({
  label,
  criteriaJson,
  accentColor,
  readOnly,
  onEdit,
  isDark,
}) => {
  const d = useEditorI18n();
  const summary = getCriteriaSummary(criteriaJson, d.criterionLabels, d.criteriaSetFallback, d.invalidJson);
  const isSet = summary !== null;

  const t1 = isDark ? 'rgba(255,255,255,0.85)' : 'rgba(0,0,0,0.82)';
  const t3 = isDark ? 'rgba(255,255,255,0.30)' : 'rgba(0,0,0,0.30)';

  return (
    <div style={{
      display: 'flex',
      alignItems: 'center',
      gap: 8,
      padding: '6px 0',
      minHeight: 34,
    }}>
      {/* Иконка статуса */}
      <span style={{ fontSize: 14, color: isSet ? accentColor : t3, flexShrink: 0 }}>
        {isSet
          ? <CheckCircleOutlined />
          : <ExclamationCircleOutlined />
        }
      </span>

      {/* Метка и сводка */}
      <div style={{ flex: 1, minWidth: 0 }}>
        <div style={{ fontSize: 11, fontWeight: 600, color: t3, textTransform: 'uppercase', letterSpacing: '0.06em' }}>
          {label}
        </div>
        <div style={{
          fontSize: 12,
          color: isSet ? t1 : t3,
          overflow: 'hidden',
          textOverflow: 'ellipsis',
          whiteSpace: 'nowrap',
        }}>
          {isSet ? summary : d.criteriaNotSet}
        </div>
      </div>

      {/* Кнопка редактирования */}
      {!readOnly && (
        <Button
          type="text"
          size="small"
          icon={<EditOutlined />}
          onClick={onEdit}
          style={{ flexShrink: 0, fontSize: 11 }}
        >
          {d.editCriteria}
        </Button>
      )}
    </div>
  );
};

// ── Главный компонент ─────────────────────────────────────────────────────────

export const StartStopPanel: React.FC<StartStopPanelProps> = ({
  startCriteriaJson,
  stopCriteriaJson,
  readOnly = false,
  onSave,
}) => {
  const d = useEditorI18n();
  const { isDark } = useTheme();

  const [editTarget, setEditTarget] = useState<'start' | 'stop' | null>(null);
  const [draftJson, setDraftJson] = useState<string>('');

  const bg      = isDark ? 'rgba(255,255,255,0.04)' : 'rgba(0,0,0,0.03)';
  const bd      = isDark ? 'rgba(255,255,255,0.08)' : 'rgba(0,0,0,0.08)';
  const t2      = isDark ? 'rgba(255,255,255,0.55)' : 'rgba(0,0,0,0.52)';

  const openEdit = (target: 'start' | 'stop') => {
    const current = target === 'start' ? startCriteriaJson : stopCriteriaJson;
    setDraftJson(current ?? '');
    setEditTarget(target);
  };

  const handleSave = () => {
    if (editTarget === 'start') {
      onSave(draftJson.trim() || null, stopCriteriaJson);
    } else {
      onSave(startCriteriaJson, draftJson.trim() || null);
    }
    setEditTarget(null);
  };

  const handleCancel = () => {
    setEditTarget(null);
    setDraftJson('');
  };

  return (
    <>
      <div style={{
        background: bg,
        border: `1px solid ${bd}`,
        borderRadius: 10,
        padding: '10px 14px',
        marginBottom: 12,
      }}>
        {/* Заголовок */}
        <div style={{
          display: 'flex',
          alignItems: 'center',
          gap: 6,
          marginBottom: 8,
        }}>
          <span style={{ fontSize: 12, fontWeight: 700, color: t2, textTransform: 'uppercase', letterSpacing: '0.07em' }}>
            {d.startStopTitle}
          </span>
          <Tooltip title={d.criteriaHint}>
            <InfoCircleOutlined style={{ fontSize: 11, color: t2, cursor: 'help' }} />
          </Tooltip>
          <Tag style={{
            fontSize: 9,
            padding: '0 5px',
            height: 16,
            lineHeight: '16px',
            marginLeft: 'auto',
            color: t2,
            borderColor: bd,
            background: 'transparent',
          }}>
            P7-3
          </Tag>
        </div>

        {/* Строки критериев */}
        <CriteriaRow
          label={d.startCriteria}
          criteriaJson={startCriteriaJson}
          accentColor={isDark ? '#30d158' : '#15803d'}
          readOnly={readOnly}
          onEdit={() => openEdit('start')}
          isDark={isDark}
        />
        <div style={{ height: 1, background: bd, margin: '2px 0' }} />
        <CriteriaRow
          label={d.stopCriteria}
          criteriaJson={stopCriteriaJson}
          accentColor={isDark ? '#ff453a' : '#b91c1c'}
          readOnly={readOnly}
          onEdit={() => openEdit('stop')}
          isDark={isDark}
        />
      </div>

      {/* Конструктор критериев (P7-3) */}
      <Modal
        title={editTarget === 'start' ? d.startCriteria : d.stopCriteria}
        open={editTarget !== null}
        onOk={handleSave}
        onCancel={handleCancel}
        okText={d.save}
        cancelText={d.cancel}
        width={680}
        destroyOnClose
      >
        <div style={{ marginBottom: 10, fontSize: 12, color: t2 }}>
          {d.criteriaHint}
        </div>
        <CriteriaBuilder
          value={draftJson}
          onChange={setDraftJson}
        />
      </Modal>
    </>
  );
};
