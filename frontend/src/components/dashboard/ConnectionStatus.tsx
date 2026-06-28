/**
 * ConnectionStatus — индикатор состояния WS-подключения (P7-4).
 *
 * Отображается в хедере. Читает флаг `connected` из useInstancesStore.
 * Флаг является оптимистичным: true после вызова connect(), false после disconnect().
 */
import React from 'react';
import { Tooltip, Badge } from 'antd';
import { useInstancesStore } from '../../store/instancesStore';
import { useEditorI18n } from '../../i18n/useEditorI18n';

export const ConnectionStatus: React.FC = () => {
  const connected = useInstancesStore((s) => s.connected);
  const d = useEditorI18n();

  return (
    <Tooltip title={connected ? d.wsConnected : d.wsDisconnected}>
      <Badge
        status={connected ? 'success' : 'error'}
        text={connected ? d.wsConnected : d.wsDisconnected}
        style={{ fontSize: 11, whiteSpace: 'nowrap', cursor: 'default' }}
      />
    </Tooltip>
  );
};
