import React, { useState } from 'react';
import { Input, Alert } from 'antd';
import { validateCriteriaJson } from '../../utils/criteriaUtils';

const { TextArea } = Input;

interface CriteriaEditorProps {
  value?: string;
  onChange?: (value: string) => void;
}

export const CriteriaEditor: React.FC<CriteriaEditorProps> = ({ value = '', onChange }) => {
  const [error, setError] = useState<string | null>(null);

  const handleChange = (e: React.ChangeEvent<HTMLTextAreaElement>) => {
    const newValue = e.target.value;

    if (newValue.trim() === '') {
      setError(null);
      onChange?.(newValue);
      return;
    }

    if (validateCriteriaJson(newValue)) {
      setError(null);
      onChange?.(newValue);
    } else {
      setError('Некорректный формат JSON');
      onChange?.(newValue);
    }
  };

  return (
    <div>
      <TextArea
        rows={8}
        value={value}
        onChange={handleChange}
        placeholder='{"type": "FLIGHT_STAGE", "operator": "EQUALS", "targetStage": "OUT"}'
        style={{ fontFamily: 'monospace', fontSize: '12px' }}
      />
      {error && (
        <Alert
          message={error}
          type="error"
          style={{ marginTop: 8 }}
          showIcon
        />
      )}
      <div style={{ marginTop: 8, fontSize: '12px', color: '#8c8c8c' }}>
        Введите критерий в формате JSON или оставьте поле пустым
      </div>
    </div>
  );
};
