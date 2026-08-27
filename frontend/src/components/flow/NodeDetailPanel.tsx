import React from 'react';
import { Tag } from 'antd';
import { ApartmentOutlined, InfoCircleOutlined } from '@ant-design/icons';
import type { Node } from '@xyflow/react';
import { getTypeAccent, getStateTokens, type StepNodeState } from '../../utils/stepTypeColors';

interface NodeDetailPanelProps {
  selectedNode: Node | null;
  onClose: () => void;
  isDark: boolean;
}

export const NodeDetailPanel: React.FC<NodeDetailPanelProps> = ({
  selectedNode,
  onClose,
  isDark,
}) => {
  const t1 = isDark ? 'rgba(255,255,255,0.88)' : 'rgba(0,0,0,0.82)';
  const t2 = isDark ? 'rgba(255,255,255,0.52)' : 'rgba(0,0,0,0.50)';
  const t3 = isDark ? 'rgba(255,255,255,0.28)' : 'rgba(0,0,0,0.28)';
  const bd = isDark ? 'rgba(255,255,255,0.07)' : 'rgba(0,0,0,0.07)';

  /* ── Empty state ──────────────────────────────── */
  if (!selectedNode) {
    return (
      <div style={{
        height: '100%',
        display: 'flex',
        flexDirection: 'column',
        alignItems: 'center',
        justifyContent: 'center',
        padding: 24,
        gap: 12,
      }}>
        <ApartmentOutlined style={{ fontSize: 36, color: t3 }} />
        <div style={{ fontSize: 13, fontWeight: 500, color: t2, textAlign: 'center', lineHeight: 1.55 }}>
          Нажмите на узел<br />для просмотра деталей
        </div>
        <div style={{ fontSize: 11, color: t3, textAlign: 'center', lineHeight: 1.6 }}>
          Тип шага, конфигурация<br />и состояние выполнения
        </div>
      </div>
    );
  }

  /* ── Extract node data ── */
  const d = selectedNode.data as Record<string, unknown>;

  const title     = (d.configLabel as string | undefined) || (d.label as string | undefined) || '—';
  const stepNum   = (d.orderIndex as number | undefined) ?? (d.stepNumber as number | undefined);
  const stepType  = (d.stepType as string | undefined)
    || (selectedNode.type ? selectedNode.type.toUpperCase() : undefined);
  const rawState  = ((d.state as string | undefined) ?? 'idle') as StepNodeState;

  const STATE_LABEL: Record<StepNodeState, string> = {
    idle:      'Ожидание',
    active:    'Выполняется',
    success:   'Завершено',
    failure:   'Ошибка',
    unreached: 'Не достигнут',
  };

  const sc = getStateTokens(rawState, isDark).border;
  const tc = stepType === 'ACTION' || stepType === 'EVALUATE' || stepType === 'WAIT'
    ? getTypeAccent(stepType, isDark)
    : t2;

  const detailRows: { label: string; value: string }[] = [
    stepType && { label: 'Тип шага', value: stepType },
    stepNum !== undefined && { label: 'Порядковый номер', value: `#${stepNum}` },
  ].filter(Boolean) as { label: string; value: string }[];

  return (
    <div style={{ height: '100%', display: 'flex', flexDirection: 'column', overflow: 'hidden' }}>

      {/* ── Header ── */}
      <div style={{
        padding: '12px 14px',
        borderBottom: `1px solid ${bd}`,
        display: 'flex',
        justifyContent: 'space-between',
        alignItems: 'flex-start',
        flexShrink: 0,
        gap: 8,
      }}>
        <div style={{ flex: 1, minWidth: 0 }}>
          <div style={{
            fontSize: 13, fontWeight: 700, color: t1,
            marginBottom: 6, lineHeight: 1.4,
            wordBreak: 'break-word',
          }}>
            {title}
          </div>
          <div style={{ display: 'flex', gap: 5, flexWrap: 'wrap' }}>
            {stepNum !== undefined && (
              <Tag style={{ fontSize: 10, margin: 0, lineHeight: '18px' }}>Шаг #{stepNum}</Tag>
            )}
            {stepType && (
              <Tag style={{
                fontSize: 10, margin: 0, lineHeight: '18px',
                color: tc, borderColor: `${tc}45`, background: `${tc}14`,
              }}>
                {stepType}
              </Tag>
            )}
          </div>
        </div>
        <button
          onClick={onClose}
          className="panel-btn-press"
          style={{
            background: 'none', border: 'none', cursor: 'pointer',
            color: t3, fontSize: 20, lineHeight: 1,
            padding: 0, flexShrink: 0, marginTop: -2,
            transition: 'color 0.15s ease, transform 0.12s var(--ease-out)',
          }}
          onMouseEnter={e => { (e.currentTarget as HTMLButtonElement).style.color = t1; }}
          onMouseLeave={e => { (e.currentTarget as HTMLButtonElement).style.color = t3; }}
        >
          ×
        </button>
      </div>

      {/* ── Body ── */}
      <div style={{ flex: 1, overflowY: 'auto', padding: '12px 14px' }}>

        {/* State chip */}
        <div style={{
          padding: '7px 12px',
          borderRadius: 10,
          background: `${sc}14`,
          border: `1px solid ${sc}28`,
          marginBottom: 12,
          display: 'flex',
          alignItems: 'center',
          gap: 8,
        }}>
          <span style={{
            width: 7, height: 7, borderRadius: '50%',
            background: sc, display: 'inline-block', flexShrink: 0,
          }} />
          <span style={{ fontSize: 13, fontWeight: 600, color: sc }}>
            {STATE_LABEL[rawState] ?? rawState}
          </span>
        </div>

        {/* Detail rows */}
        {detailRows.map((row, i) => (
          <div key={i} style={{
            padding: '7px 0',
            borderBottom: `1px solid ${bd}`,
            display: 'flex',
            justifyContent: 'space-between',
            alignItems: 'center',
            gap: 10,
          }}>
            <span style={{ fontSize: 12, color: t2 }}>{row.label}</span>
            <span style={{ fontSize: 12, color: t1, fontWeight: 600, textAlign: 'right' }}>
              {row.value}
            </span>
          </div>
        ))}

        <div style={{ marginTop: 18, fontSize: 11, color: t3, textAlign: 'center' }}>
          <InfoCircleOutlined style={{ marginRight: 4 }} />
          Нажмите на другой узел для просмотра
        </div>
      </div>
    </div>
  );
};
