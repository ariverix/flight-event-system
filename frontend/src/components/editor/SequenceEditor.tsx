/**
 * Страница визуального редактора последовательности (P7-2).
 *
 * Маршрут: /sequences/:id/editor
 *
 * Компоновка:
 *  ┌─────────────────────────────────────────────────────────────┐
 *  │ Header: [← Назад] [Название] [Unsaved badge] [Сохранить]   │
 *  ├───────────────────────────┬─────────────────────────────────┤
 *  │                           │ ┌─────────────────────────────┐ │
 *  │   React Flow (граф)       │ │  Start/Stop критерии        │ │
 *  │                           │ └─────────────────────────────┘ │
 *  │   - Click node → select   │ ┌─────────────────────────────┐ │
 *  │   - Canvas drag → repos.  │ │  Список шагов               │ │
 *  │                           │ │  (drag-and-drop reorder)    │ │
 *  │                           │ └─────────────────────────────┘ │
 *  │                           │ ┌─────────────────────────────┐ │
 *  │                           │ │  Детали выбранного шага     │ │
 *  │                           │ └─────────────────────────────┘ │
 *  └───────────────────────────┴─────────────────────────────────┘
 *
 * Реализует:
 *  1. Загрузку последовательности через sequenceEditorStore
 *  2. Интерактивный граф (SequenceEditorGraph)
 *  3. Drag-n-drop перестановку шагов с GOTO-пересчётом (EditorStepList)
 *  4. Панель start/stop-критериев (StartStopPanel)
 *  5. Сохранение через sequenceEditorStore.saveToServer()
 *  6. Модал добавления/редактирования шагов (StepForm, P7-3 заменит)
 */

import React, { useEffect, useState, useCallback } from 'react';
import {
  Button,
  Spin,
  Alert,
  Modal,
  Tag,
  Divider,
  Tooltip,
  InputNumber,
} from 'antd';
import {
  ArrowLeftOutlined,
  SaveOutlined,
  WarningOutlined,
  InfoCircleOutlined,
  SettingOutlined,
} from '@ant-design/icons';
import { useNavigate, useParams } from 'react-router-dom';

import { useSequenceEditorStore } from '../../store/sequenceEditorStore';
import { SequenceEditorGraph } from './SequenceEditorGraph';
import { EditorStepList } from './EditorStepList';
import { StartStopPanel } from './StartStopPanel';
import { StepFormV2 } from './StepFormV2';
import { useEditorI18n } from '../../i18n/useEditorI18n';
import { useTheme } from '../../context/ThemeContext';
import { useAuth } from '../../hooks/useAuth';
import { useNotification } from '../../hooks/useNotification';
import type { StepResponse, StepCreateRequest, SequenceStatus } from '../../types/sequence';
import { sequenceApi } from '../../api/sequenceApi';
import { getTypeAccent } from '../../utils/stepTypeColors';

// ── Панель свойств последовательности ────────────────────────────────────────

interface SeqPropertiesPanelProps {
  status: SequenceStatus;
  folderIdInput: number | null;
  onFolderIdChange: (v: number | null) => void;
  onActivate: () => void;
  onDeactivate: () => void;
  onAssignFolder: () => void;
  isDark: boolean;
}

const SeqPropertiesPanel: React.FC<SeqPropertiesPanelProps> = ({
  status,
  folderIdInput,
  onFolderIdChange,
  onActivate,
  onDeactivate,
  onAssignFolder,
  isDark,
}) => {
  const d = useEditorI18n();
  const bd  = isDark ? 'rgba(255,255,255,0.08)' : 'rgba(0,0,0,0.08)';
  const t2  = isDark ? 'rgba(255,255,255,0.55)' : 'rgba(0,0,0,0.50)';

  const statusLabel: Record<string, string> = {
    DRAFT:    d.seqStatusDraft,
    ACTIVE:   d.seqStatusActive,
    INACTIVE: d.seqStatusInactive,
  };
  const statusColor: Record<string, string> = {
    DRAFT:    'default',
    ACTIVE:   'success',
    INACTIVE: 'warning',
  };

  return (
    <div style={{ padding: '4px 0' }}>
      {/* Статус */}
      <div style={{ marginBottom: 16 }}>
        <div style={{ fontSize: 12, fontWeight: 600, color: t2, marginBottom: 8 }}>
          {d.seqStatusLabel}
        </div>
        <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
          <Tag color={statusColor[status] ?? 'default'} style={{ fontSize: 12 }}>
            {statusLabel[status] ?? status}
          </Tag>
          {status !== 'ACTIVE' && (
            <Button size="small" type="primary" onClick={onActivate}>
              {d.seqActivateBtn}
            </Button>
          )}
          {status === 'ACTIVE' && (
            <Button size="small" danger onClick={onDeactivate}>
              {d.seqDeactivateBtn}
            </Button>
          )}
        </div>
      </div>

      <Divider style={{ margin: '12px 0', borderColor: bd }} />

      {/* Папка */}
      <div>
        <div style={{ fontSize: 12, fontWeight: 600, color: t2, marginBottom: 8 }}>
          {d.seqFolderLabel}
        </div>
        <div style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
          <InputNumber
            min={1}
            value={folderIdInput}
            placeholder={d.seqFolderNone}
            style={{ width: 140 }}
            onChange={v => onFolderIdChange(v)}
          />
          <Button size="small" onClick={onAssignFolder}>
            {d.seqFolderAssignBtn}
          </Button>
          {folderIdInput !== null && (
            <Button size="small" type="text" danger onClick={() => { onFolderIdChange(null); onAssignFolder(); }}>
              {d.seqFolderNone}
            </Button>
          )}
        </div>
        <div style={{ fontSize: 11, color: t2, marginTop: 4 }}>{d.seqFolderIdLabel}</div>
      </div>
    </div>
  );
};

// ── Панель деталей выбранного шага ───────────────────────────────────────────

interface SelectedStepPanelProps {
  step: StepResponse | null;
  isDark: boolean;
}

// Метки типов конфигурации берутся из d.configLabels (dict.ts)

const SelectedStepPanel: React.FC<SelectedStepPanelProps> = ({ step, isDark }) => {
  const d = useEditorI18n();
  const t1 = isDark ? 'rgba(255,255,255,0.88)' : 'rgba(0,0,0,0.82)';
  const t2 = isDark ? 'rgba(255,255,255,0.55)' : 'rgba(0,0,0,0.50)';
  const t3 = isDark ? 'rgba(255,255,255,0.28)' : 'rgba(0,0,0,0.28)';
  const bd = isDark ? 'rgba(255,255,255,0.08)' : 'rgba(0,0,0,0.07)';
  const bg = isDark ? 'rgba(255,255,255,0.03)' : 'rgba(0,0,0,0.02)';

  if (!step) {
    return (
      <div style={{
        background: bg,
        border: `1px solid ${bd}`,
        borderRadius: 10,
        padding: '14px',
        textAlign: 'center',
      }}>
        <InfoCircleOutlined style={{ fontSize: 20, color: t3, marginBottom: 6 }} />
        <div style={{ fontSize: 12, color: t3 }}>{d.clickNodeHint}</div>
      </div>
    );
  }

  const accent = getTypeAccent(step.stepType, isDark);

  let configSummary = '—';
  try {
    const cfg = JSON.parse(step.configJson) as Record<string, unknown>;
    const key = (cfg.actionType ?? cfg.type ?? cfg.criterionType) as string | undefined;
    configSummary = key ? (d.configLabels[key] ?? key) : '—';
    if (typeof cfg.templateName === 'string') configSummary += `: ${cfg.templateName}`;
    else if (typeof cfg.conditionName === 'string') configSummary += `: ${cfg.conditionName}`;
    else if (typeof cfg.targetStage === 'string') configSummary += `: ${cfg.targetStage}`;
    else if (typeof cfg.durationSeconds === 'number') configSummary += `: ${cfg.durationSeconds}s`;
  } catch {
    configSummary = '—';
  }

  const decisionText = (action: string, gotoStep: number | null): string => {
    if (action === 'GOTO' && gotoStep !== null) return `GOTO ${d.gotoPrefix} ${gotoStep}`;
    return action;
  };

  return (
    <div style={{
      background: bg,
      border: `1px solid ${bd}`,
      borderRadius: 10,
      padding: '12px 14px',
    }}>
      {/* Заголовок */}
      <div style={{ display: 'flex', alignItems: 'center', gap: 6, marginBottom: 10 }}>
        <div style={{
          width: 3, height: 36, borderRadius: 2,
          background: accent, flexShrink: 0,
        }} />
        <div>
          <div style={{ fontSize: 10, color: t3, fontWeight: 600, textTransform: 'uppercase', letterSpacing: '0.06em' }}>
            {d.selectedStep} · {d.stepLabel} #{step.orderIndex}
          </div>
          <div style={{ fontSize: 13, fontWeight: 600, color: t1 }}>
            {configSummary}
          </div>
        </div>
        <Tag style={{
          marginLeft: 'auto',
          color: accent,
          borderColor: `${accent}44`,
          background: `${accent}14`,
          fontSize: 10,
          flexShrink: 0,
        }}>
          {step.stepType}
        </Tag>
      </div>

      <Divider style={{ margin: '8px 0', borderColor: bd }} />

      {/* Решения */}
      <div style={{ display: 'flex', gap: 10 }}>
        <div style={{ flex: 1 }}>
          <div style={{ fontSize: 10, fontWeight: 600, color: 'var(--accent-green)', marginBottom: 4 }}>
            {d.onSuccess}
            {step.onSuccessNotify && (
              <span style={{ marginLeft: 4, color: t3 }}>{d.notifyLabel}</span>
            )}
          </div>
          <div style={{ fontSize: 12, color: t2 }}>
            {decisionText(step.onSuccessAction, step.onSuccessGotoStep)}
          </div>
        </div>
        <div style={{ flex: 1 }}>
          <div style={{ fontSize: 10, fontWeight: 600, color: 'var(--accent-red)', marginBottom: 4 }}>
            {d.onFailure}
            {step.onFailureNotify && (
              <span style={{ marginLeft: 4, color: t3 }}>{d.notifyLabel}</span>
            )}
          </div>
          <div style={{ fontSize: 12, color: t2 }}>
            {decisionText(step.onFailureAction, step.onFailureGotoStep)}
          </div>
        </div>
      </div>

      {/* configJson (свёрнутый) */}
      <Divider style={{ margin: '8px 0', borderColor: bd }} />
      <details style={{ cursor: 'pointer' }}>
        <summary style={{ fontSize: 10, color: t3, userSelect: 'none' }}>
          {d.configJson}
        </summary>
        <pre style={{
          marginTop: 6,
          fontSize: 10,
          color: isDark ? '#30d158' : '#15803d',
          background: isDark ? 'rgba(0,0,0,0.3)' : 'rgba(0,0,0,0.04)',
          borderRadius: 6,
          padding: '8px 10px',
          overflow: 'auto',
          maxHeight: 120,
        }}>
          {(() => {
            try {
              return JSON.stringify(JSON.parse(step.configJson), null, 2);
            } catch {
              return step.configJson;
            }
          })()}
        </pre>
      </details>
    </div>
  );
};

// ── Главный компонент ─────────────────────────────────────────────────────────

export const SequenceEditor: React.FC = () => {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const d = useEditorI18n();
  const { isDark } = useTheme();
  const { isAdmin } = useAuth();
  const notification = useNotification();

  // ─ Стор ──────────────────────────────────────────────────────────────────
  const {
    sequenceName,
    sequenceStatus,
    steps,
    startCriteriaJson,
    stopCriteriaJson,
    selectedStepId,
    isDirty,
    isLoading,
    isSaving,
    loadError,
    saveError,
    loadSequence,
    reorderStepsLocally,
    selectStep,
    updateCriteria,
    reloadAfterStepChange,
    deleteStep,
    saveToServer,
    reset,
  } = useSequenceEditorStore();

  // ─ Модал StepFormV2 ───────────────────────────────────────────────────────
  const [isStepModalOpen, setIsStepModalOpen] = useState(false);
  const [editingStep, setEditingStep] = useState<StepResponse | null>(null);

  // ─ Модал свойств последовательности ──────────────────────────────────────
  const [isPropsModalOpen, setIsPropsModalOpen] = useState(false);
  const [propsFolderIdInput, setPropsFolderIdInput] = useState<number | null>(null);

  // ─ Загрузка ───────────────────────────────────────────────────────────────
  useEffect(() => {
    if (!id || id === 'new') {
      navigate('/sequences');
      return;
    }
    const numId = parseInt(id, 10);
    if (Number.isNaN(numId)) {
      navigate('/sequences');
      return;
    }
    void loadSequence(numId);
    return () => { reset(); };
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [id]);

  // ─ Показ ошибки сохранения ────────────────────────────────────────────────
  useEffect(() => {
    if (saveError) {
      notification.error({ message: d.saveError, description: saveError });
    }
  }, [saveError, d.saveError, notification]);

  // ─ Колбэки ───────────────────────────────────────────────────────────────
  const handleSave = useCallback(async () => {
    await saveToServer();
    notification.success({ message: d.saveSuccess });
  }, [saveToServer, notification, d.saveSuccess]);

  const handleAddStep = useCallback(() => {
    setEditingStep(null);
    setIsStepModalOpen(true);
  }, []);

  const handleEditStep = useCallback((step: StepResponse) => {
    setEditingStep(step);
    setIsStepModalOpen(true);
  }, []);

  const handleStepSubmit = useCallback(async (stepData: StepCreateRequest) => {
    if (!id) return;
    const numId = parseInt(id, 10);
    try {
      if (editingStep) {
        await sequenceApi.updateStep(numId, editingStep.id, stepData);
        notification.success({ message: d.stepUpdated });
      } else {
        await sequenceApi.addStep(numId, stepData);
        notification.success({ message: d.stepAdded });
      }
      setIsStepModalOpen(false);
      setEditingStep(null);
      await reloadAfterStepChange();
    } catch (err) {
      const msg = err instanceof Error ? err.message : String(err);
      notification.error({ message: d.stepSaveError, description: msg });
    }
  }, [id, editingStep, reloadAfterStepChange, notification, d]);

  const handleDeleteStep = useCallback(async (stepId: number) => {
    await deleteStep(stepId);
    if (selectedStepId === stepId) selectStep(null);
    notification.success({ message: d.stepDeleted });
  }, [deleteStep, selectedStepId, selectStep, notification, d]);

  const handleActivate = useCallback(async () => {
    if (!id) return;
    try {
      await sequenceApi.activateSequence(parseInt(id, 10));
      notification.success({ message: d.seqActivated });
      await reloadAfterStepChange();
    } catch (err) {
      const msg = err instanceof Error ? err.message : String(err);
      notification.error({ message: d.seqActivateError, description: msg });
    }
  }, [id, reloadAfterStepChange, notification, d]);

  const handleDeactivate = useCallback(async () => {
    if (!id) return;
    try {
      await sequenceApi.deactivateSequence(parseInt(id, 10));
      notification.success({ message: d.seqDeactivated });
      await reloadAfterStepChange();
    } catch (err) {
      const msg = err instanceof Error ? err.message : String(err);
      notification.error({ message: d.seqDeactivateError, description: msg });
    }
  }, [id, reloadAfterStepChange, notification, d]);

  const handleAssignFolder = useCallback(async () => {
    if (!id) return;
    try {
      await sequenceApi.assignFolder(parseInt(id, 10), propsFolderIdInput);
      notification.success({ message: d.seqFolderAssigned });
    } catch (err) {
      const msg = err instanceof Error ? err.message : String(err);
      notification.error({ message: d.seqFolderError, description: msg });
    }
  }, [id, propsFolderIdInput, notification, d]);

  // ─ Выбранный шаг для деталей ─────────────────────────────────────────────
  const selectedStep = steps.find(s => s.id === selectedStepId) ?? null;

  // ─ Цвета темы ────────────────────────────────────────────────────────────
  const headerBg = isDark ? '#2c2c2e' : '#ffffff';
  const headerBd = isDark ? 'rgba(255,255,255,0.12)' : 'rgba(0,0,0,0.10)';
  const panelBg  = isDark ? '#2c2c2e' : '#ffffff';
  const panelBd  = isDark ? 'rgba(255,255,255,0.12)' : 'rgba(0,0,0,0.10)';
  const t1       = isDark ? 'rgba(255,255,255,0.90)' : 'rgba(0,0,0,0.85)';
  const t2       = isDark ? 'rgba(255,255,255,0.52)' : 'rgba(0,0,0,0.45)';

  // ─ Состояния загрузки ─────────────────────────────────────────────────────
  if (isLoading) {
    return (
      <div style={{
        display: 'flex',
        justifyContent: 'center',
        alignItems: 'center',
        height: '100vh',
      }}>
        <Spin size="large" />
      </div>
    );
  }

  if (loadError) {
    return (
      <div style={{ padding: 24 }}>
        <Alert
          type="error"
          message={d.loadError}
          description={loadError}
          action={
            <Button onClick={() => navigate('/sequences')}>
              {d.back}
            </Button>
          }
        />
      </div>
    );
  }

  // ─ Рендер ────────────────────────────────────────────────────────────────
  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100vh' }}>

      {/* ── Заголовок ── */}
      <div style={{
        display: 'flex',
        alignItems: 'center',
        gap: 12,
        padding: '10px 16px',
        background: headerBg,
        borderBottom: `1px solid ${headerBd}`,
        flexShrink: 0,
        zIndex: 10,
      }}>
        <Button
          type="text"
          icon={<ArrowLeftOutlined />}
          onClick={() => navigate(`/sequences/${id ?? ''}`)}
          style={{ padding: '0 8px', color: t2 }}
        >
          {d.back}
        </Button>

        <div style={{ width: 1, height: 20, background: headerBd }} />

        <div style={{ flex: 1, minWidth: 0 }}>
          <div style={{ fontSize: 14, fontWeight: 700, color: t1, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
            {sequenceName || d.title}
          </div>
          <div style={{ fontSize: 11, color: t2 }}>
            {d.title}
          </div>
        </div>

        {isDirty && (
          <Tooltip title={d.unsavedBadge}>
            <Tag
              icon={<WarningOutlined />}
              color="warning"
              style={{ cursor: 'default', flexShrink: 0 }}
            >
              {d.unsavedBadge}
            </Tag>
          </Tooltip>
        )}

        {isAdmin && (
          <Button
            icon={<SettingOutlined />}
            onClick={() => setIsPropsModalOpen(true)}
            style={{ flexShrink: 0 }}
          >
            {d.seqPropertiesBtn}
          </Button>
        )}

        {isAdmin && (
          <Button
            type="primary"
            icon={<SaveOutlined />}
            loading={isSaving}
            disabled={!isDirty}
            onClick={() => { void handleSave(); }}
            style={{ flexShrink: 0 }}
          >
            {isSaving ? d.saving : d.save}
          </Button>
        )}
      </div>

      {/* ── Основная область ── */}
      <div style={{ display: 'flex', flex: 1, overflow: 'hidden' }}>

        {/* ── Граф (левая часть) ── */}
        <div style={{ flex: 1, minWidth: 0, position: 'relative' }}>
          <SequenceEditorGraph
            steps={steps}
            selectedStepId={selectedStepId}
            onNodeSelect={selectStep}
            height="100%"
          />
        </div>

        {/* ── Правая панель ── */}
        <div style={{
          width: 320,
          flexShrink: 0,
          borderLeft: `1px solid ${panelBd}`,
          background: panelBg,
          display: 'flex',
          flexDirection: 'column',
          overflow: 'hidden',
        }}>
          <div style={{ flex: 1, overflowY: 'auto', padding: '12px 12px' }}>

            {/* Start/Stop критерии */}
            <StartStopPanel
              startCriteriaJson={startCriteriaJson}
              stopCriteriaJson={stopCriteriaJson}
              readOnly={!isAdmin}
              onSave={updateCriteria}
            />

            {/* Список шагов */}
            <EditorStepList
              steps={steps}
              selectedStepId={selectedStepId}
              readOnly={!isAdmin}
              onSelectStep={stepId => selectStep(stepId)}
              onReorder={reorderStepsLocally}
              onEditStep={handleEditStep}
              onDeleteStep={stepId => { void handleDeleteStep(stepId); }}
              onAddStep={handleAddStep}
            />

            {/* Детали выбранного шага */}
            <div style={{ marginTop: 12 }}>
              <SelectedStepPanel step={selectedStep} isDark={isDark} />
            </div>

          </div>
        </div>
      </div>

      {/* ── Модал StepFormV2 ── */}
      <Modal
        title={editingStep ? d.editStepTitle : d.addStepTitle}
        open={isStepModalOpen}
        onCancel={() => { setIsStepModalOpen(false); setEditingStep(null); }}
        footer={null}
        width={860}
        styles={{ body: { maxHeight: '80vh', overflowY: 'auto', paddingRight: 4 } }}
        destroyOnClose
      >
        <StepFormV2
          initialValues={editingStep}
          availableSteps={steps.map(s => ({ id: s.id, orderIndex: s.orderIndex }))}
          onSubmit={stepData => { void handleStepSubmit(stepData); }}
          onCancel={() => { setIsStepModalOpen(false); setEditingStep(null); }}
        />
      </Modal>

      {/* ── Модал свойств последовательности ── */}
      <Modal
        title={d.seqPropertiesTitle}
        open={isPropsModalOpen}
        onCancel={() => setIsPropsModalOpen(false)}
        footer={null}
        width={480}
        destroyOnClose
      >
        <SeqPropertiesPanel
          status={sequenceStatus ?? 'DRAFT'}
          folderIdInput={propsFolderIdInput}
          onFolderIdChange={setPropsFolderIdInput}
          onActivate={() => { void handleActivate(); }}
          onDeactivate={() => { void handleDeactivate(); }}
          onAssignFolder={() => { void handleAssignFolder(); }}
          isDark={isDark}
        />
      </Modal>
    </div>
  );
};
