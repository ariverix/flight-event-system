/**
 * Список шагов с drag-and-drop перестановкой (P7-2).
 *
 * Реализует HTML5 Drag and Drop:
 *  - ручка (⠿) инициирует перетаскивание
 *  - при сбросе на другой шаг вызывается onReorder(newIdOrder)
 *  - родитель пересчитывает GOTO и обновляет граф
 *
 * Показывает для каждого шага:
 *  - тип (ACTION/EVALUATE/WAIT) с цветовым акцентом
 *  - краткую сводку конфигурации
 *  - решения (CONTINUE/GOTO/END/ABORT) для true и false ветки
 *  - иконку уведомления если Notify=true
 */

import React, { useState, useCallback } from 'react';
import { Button, Popconfirm, Tag, Tooltip } from 'antd';
import {
  DeleteOutlined,
  EditOutlined,
  BellOutlined,
} from '@ant-design/icons';
import type { StepResponse, TransitionAction } from '../../types/sequence';
import { useEditorI18n } from '../../i18n/useEditorI18n';
import { useTheme } from '../../context/ThemeContext';
import { getTypeAccent } from '../../utils/stepTypeColors';

// ── Константы ─────────────────────────────────────────────────────────────────

const TYPE_ABBR: Record<string, string> = {
  ACTION:   'ACT',
  EVALUATE: 'EVAL',
  WAIT:     'WAIT',
};

// ── Сводка configJson ─────────────────────────────────────────────────────────

// Метки типов конфигурации берутся из d.configLabelsShort (dict.ts)

function getConfigSummary(configJson: string, labels: Record<string, string>): string {
  try {
    const cfg = JSON.parse(configJson) as Record<string, unknown>;
    const key = (cfg.actionType ?? cfg.type ?? cfg.criterionType) as string | undefined;
    const base = key ? (labels[key] ?? key) : '—';
    if (typeof cfg.templateName === 'string') return `${base}: ${cfg.templateName}`;
    if (typeof cfg.conditionName === 'string') return `${base}: ${cfg.conditionName}`;
    if (typeof cfg.targetStage === 'string') return `${base}: ${cfg.targetStage}`;
    if (typeof cfg.durationSeconds === 'number') return `${base}: ${cfg.durationSeconds}s`;
    return base;
  } catch {
    return '—';
  }
}

// ── Чип решения ───────────────────────────────────────────────────────────────

interface DecisionChipProps {
  label: string;
  action: TransitionAction;
  gotoStep: number | null;
  notify: boolean;
  isSuccess: boolean;
  isDark: boolean;
}

const DecisionChip: React.FC<DecisionChipProps> = ({
  label,
  action,
  gotoStep,
  notify,
  isSuccess,
  isDark,
}) => {
  const d = useEditorI18n();
  const color = isSuccess
    ? (isDark ? '#30d158' : '#15803d')
    : (isDark ? '#ff453a' : '#b91c1c');

  const actionText =
    action === 'GOTO' && gotoStep !== null
      ? `GOTO→${gotoStep}`
      : action;

  return (
    <div style={{ display: 'flex', alignItems: 'center', gap: 4 }}>
      <span style={{ fontSize: 9, color: isDark ? 'rgba(255,255,255,0.35)' : 'rgba(0,0,0,0.35)', flexShrink: 0 }}>
        {label}
      </span>
      <Tag
        style={{
          fontSize: 9,
          padding: '0 4px',
          height: 14,
          lineHeight: '14px',
          margin: 0,
          color,
          borderColor: `${color}44`,
          background: `${color}12`,
        }}
      >
        {actionText}
      </Tag>
      {notify && (
        <Tooltip title={d.notifyTooltip}>
          <BellOutlined style={{ fontSize: 9, color }} />
        </Tooltip>
      )}
    </div>
  );
};

// ── Элемент шага ──────────────────────────────────────────────────────────────

interface StepItemProps {
  step: StepResponse;
  isSelected: boolean;
  isDragging: boolean;
  isDragOver: boolean;
  readOnly: boolean;
  onSelect: (id: number) => void;
  onEdit: (step: StepResponse) => void;
  onDelete: (id: number) => void;
  onDragStart: (id: number) => void;
  onDragOver: (e: React.DragEvent, id: number) => void;
  onDrop: (targetId: number) => void;
  onDragEnd: () => void;
  isDark: boolean;
}

const StepItem: React.FC<StepItemProps> = ({
  step,
  isSelected,
  isDragging,
  isDragOver,
  readOnly,
  onSelect,
  onEdit,
  onDelete,
  onDragStart,
  onDragOver,
  onDrop,
  onDragEnd,
  isDark,
}) => {
  const d = useEditorI18n();
  const accent = getTypeAccent(step.stepType, isDark);
  const t1 = isDark ? 'rgba(255,255,255,0.88)' : 'rgba(0,0,0,0.82)';
  const t3 = isDark ? 'rgba(255,255,255,0.28)' : 'rgba(0,0,0,0.28)';
  const bd = isDark ? 'rgba(255,255,255,0.08)' : 'rgba(0,0,0,0.07)';

  const selectedBg = isDark ? `${accent}18` : `${accent}10`;
  const defaultBg  = isDark ? 'rgba(255,255,255,0.02)' : 'rgba(0,0,0,0.015)';
  const dragOverBd = `2px dashed ${accent}`;

  return (
    <div
      draggable={!readOnly}
      onDragStart={() => onDragStart(step.id)}
      onDragOver={e => onDragOver(e, step.id)}
      onDrop={() => onDrop(step.id)}
      onDragEnd={onDragEnd}
      onClick={() => onSelect(step.id)}
      style={{
        display: 'flex',
        alignItems: 'flex-start',
        gap: 6,
        padding: '8px 8px 8px 6px',
        borderRadius: 8,
        border: isDragOver
          ? dragOverBd
          : `1px solid ${isSelected ? `${accent}55` : bd}`,
        background: isSelected ? selectedBg : defaultBg,
        opacity: isDragging ? 0.4 : 1,
        cursor: readOnly ? 'pointer' : 'grab',
        transition: 'border-color 0.15s, background 0.15s, opacity 0.15s',
        marginBottom: 4,
        userSelect: 'none',
      }}
    >
      {/* Ручка drag-and-drop */}
      {!readOnly && (
        <span
          title={d.dragHint}
          style={{
            fontSize: 14,
            color: t3,
            cursor: 'grab',
            flexShrink: 0,
            lineHeight: 1,
            paddingTop: 2,
          }}
        >
          ⠿
        </span>
      )}

      {/* Акцент-полоска типа шага */}
      <div style={{
        width: 3,
        alignSelf: 'stretch',
        borderRadius: 2,
        background: accent,
        flexShrink: 0,
      }} />

      {/* Контент шага */}
      <div style={{ flex: 1, minWidth: 0 }}>
        {/* Верхняя строка: номер + тип + сводка */}
        <div style={{ display: 'flex', alignItems: 'center', gap: 5, marginBottom: 4 }}>
          <span style={{ fontSize: 10, fontWeight: 700, color: t3, flexShrink: 0 }}>
            #{step.orderIndex}
          </span>
          <Tag style={{
            fontSize: 9,
            padding: '0 4px',
            height: 14,
            lineHeight: '14px',
            margin: 0,
            color: accent,
            borderColor: `${accent}44`,
            background: `${accent}14`,
          }}>
            {TYPE_ABBR[step.stepType] ?? step.stepType}
          </Tag>
          <span style={{
            fontSize: 11,
            color: t1,
            fontWeight: 500,
            overflow: 'hidden',
            textOverflow: 'ellipsis',
            whiteSpace: 'nowrap',
          }}>
            {getConfigSummary(step.configJson, d.configLabelsShort)}
          </span>
        </div>

        {/* Нижняя строка: решения */}
        <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
          <DecisionChip
            label={d.onSuccess}
            action={step.onSuccessAction}
            gotoStep={step.onSuccessGotoStep}
            notify={step.onSuccessNotify}
            isSuccess
            isDark={isDark}
          />
          <DecisionChip
            label={d.onFailure}
            action={step.onFailureAction}
            gotoStep={step.onFailureGotoStep}
            notify={step.onFailureNotify}
            isSuccess={false}
            isDark={isDark}
          />
        </div>
      </div>

      {/* Кнопки действий */}
      {!readOnly && (
        <div
          style={{ display: 'flex', gap: 2, flexShrink: 0 }}
          onClick={e => e.stopPropagation()} // не выбирать шаг при клике на кнопки
        >
          <Tooltip title={d.editStep}>
            <Button
              type="text"
              size="small"
              icon={<EditOutlined />}
              onClick={() => onEdit(step)}
              aria-label={d.editStep}
              style={{ padding: '0 4px', height: 22, fontSize: 11 }}
            />
          </Tooltip>
          <Popconfirm
            title={d.deleteConfirm}
            description={d.deleteWarning}
            onConfirm={() => onDelete(step.id)}
            okText={d.confirmYes}
            cancelText={d.confirmNo}
            placement="left"
          >
            <Tooltip title={d.deleteStep}>
              <Button
                type="text"
                size="small"
                danger
                icon={<DeleteOutlined />}
                aria-label={d.deleteStep}
                style={{ padding: '0 4px', height: 22, fontSize: 11 }}
              />
            </Tooltip>
          </Popconfirm>
        </div>
      )}
    </div>
  );
};

// ── Главный список ────────────────────────────────────────────────────────────

export interface EditorStepListProps {
  steps: StepResponse[];
  selectedStepId: number | null;
  readOnly?: boolean;
  onSelectStep: (stepId: number | null) => void;
  onReorder: (newIdOrder: number[]) => void;
  onEditStep: (step: StepResponse) => void;
  onDeleteStep: (stepId: number) => void;
  onAddStep: () => void;
}

export const EditorStepList: React.FC<EditorStepListProps> = ({
  steps,
  selectedStepId,
  readOnly = false,
  onSelectStep,
  onReorder,
  onEditStep,
  onDeleteStep,
  onAddStep,
}) => {
  const d = useEditorI18n();
  const { isDark } = useTheme();

  const [draggedId, setDraggedId] = useState<number | null>(null);
  const [dragOverId, setDragOverId] = useState<number | null>(null);

  const t2 = isDark ? 'rgba(255,255,255,0.52)' : 'rgba(0,0,0,0.45)';
  const t3 = isDark ? 'rgba(255,255,255,0.28)' : 'rgba(0,0,0,0.28)';
  const bd = isDark ? 'rgba(255,255,255,0.08)' : 'rgba(0,0,0,0.07)';

  // Шаги уже отсортированы по orderIndex (из стора)

  const handleDragStart = useCallback((stepId: number) => {
    setDraggedId(stepId);
  }, []);

  const handleDragOver = useCallback((e: React.DragEvent, stepId: number) => {
    e.preventDefault();
    setDragOverId(stepId);
  }, []);

  const handleDrop = useCallback((targetId: number) => {
    if (draggedId === null || draggedId === targetId) {
      setDraggedId(null);
      setDragOverId(null);
      return;
    }

    // Строим новый порядок: вставляем draggedId перед targetId
    const currentIds = steps.map(s => s.id);
    const fromIdx = currentIds.indexOf(draggedId);
    if (fromIdx === -1) return;

    const withoutDragged = currentIds.filter(id => id !== draggedId);
    const toIdx = withoutDragged.indexOf(targetId);
    const newOrder =
      toIdx === -1
        ? [...withoutDragged, draggedId]  // targetId не найден — добавляем в конец
        : [
            ...withoutDragged.slice(0, toIdx),
            draggedId,
            ...withoutDragged.slice(toIdx),
          ];

    onReorder(newOrder);
    setDraggedId(null);
    setDragOverId(null);
  }, [draggedId, steps, onReorder]);

  const handleDragEnd = useCallback(() => {
    setDraggedId(null);
    setDragOverId(null);
  }, []);

  return (
    <div>
      {/* Заголовок */}
      <div style={{
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'space-between',
        marginBottom: 8,
      }}>
        <span style={{
          fontSize: 11,
          fontWeight: 700,
          color: t2,
          textTransform: 'uppercase',
          letterSpacing: '0.07em',
        }}>
          {d.steps}
          <span style={{ color: t3, marginLeft: 4 }}>({steps.length})</span>
        </span>
        {!readOnly && (
          <Button
            type="dashed"
            size="small"
            onClick={onAddStep}
            style={{ fontSize: 11 }}
          >
            + {d.addStep}
          </Button>
        )}
      </div>

      {/* Список шагов */}
      {steps.length === 0 ? (
        <div style={{
          textAlign: 'center',
          padding: '20px 12px',
          color: t3,
          fontSize: 12,
          border: `1px dashed ${bd}`,
          borderRadius: 8,
        }}>
          {d.noSteps}
        </div>
      ) : (
        <div
          onDragOver={e => e.preventDefault()} // разрешить drop на контейнер
        >
          {steps.map(step => (
            <StepItem
              key={step.id}
              step={step}
              isSelected={step.id === selectedStepId}
              isDragging={step.id === draggedId}
              isDragOver={step.id === dragOverId}
              readOnly={readOnly}
              onSelect={onSelectStep}
              onEdit={onEditStep}
              onDelete={onDeleteStep}
              onDragStart={handleDragStart}
              onDragOver={handleDragOver}
              onDrop={handleDrop}
              onDragEnd={handleDragEnd}
              isDark={isDark}
            />
          ))}
        </div>
      )}

      {/* Подсказка drag-n-drop */}
      {steps.length > 1 && !readOnly && (
        <div style={{ marginTop: 6, fontSize: 10, color: t3, textAlign: 'center' }}>
          {d.dragHint}
        </div>
      )}
    </div>
  );
};
