import React, { useState, useEffect, useMemo } from 'react';
import { Form, Select, Input, InputNumber, Button, Space, Checkbox, Divider, Typography } from 'antd';
import {
  StepCreateRequest,
  StepResponse,
  StepType,
  ActionType,
  CriterionType,
  TransitionAction,
} from '../../types/sequence';
import { useTheme } from '../../context/ThemeContext';

const { Text } = Typography;

interface StepFormProps {
  onSubmit: (data: StepCreateRequest) => void;
  onCancel: () => void;
  initialValues?: StepResponse | null;
}

export const StepForm: React.FC<StepFormProps> = ({ onSubmit, onCancel, initialValues }) => {
  const [form] = Form.useForm();
  const [stepType, setStepType] = useState<StepType>('ACTION');
  const [actionType, setActionType] = useState<ActionType | null>(null);
  const [criterionType, setCriterionType] = useState<CriterionType | null>(null);
  const [onSuccessAction, setOnSuccessAction] = useState<TransitionAction>('CONTINUE');
  const [onFailureAction, setOnFailureAction] = useState<TransitionAction>('ABORT');
  const [formValues, setFormValues] = useState<any>({});
  const { isDark } = useTheme();
  const previewBg = isDark ? '#0d1117' : '#f6f8fa';
  const previewBorder = isDark ? '#30363d' : '#d0d7de';

  useEffect(() => {
    if (initialValues) {
      let config: any = {};
      try { config = JSON.parse(initialValues.configJson); } catch { /* keep empty config */ }
      setStepType(initialValues.stepType);
      setOnSuccessAction(initialValues.onSuccessAction);
      setOnFailureAction(initialValues.onFailureAction);

      if (initialValues.stepType === 'ACTION') {
        setActionType(config.actionType);
      } else {
        // старый UI писал 'criterionType', бэкенд и миграции используют 'type'
        const ct = config.type || config.criterionType;
        setCriterionType(ct);
      }

      form.setFieldsValue({
        stepType: initialValues.stepType,
        onSuccessAction: initialValues.onSuccessAction,
        onSuccessGotoStep: initialValues.onSuccessGotoStep,
        onSuccessNotify: initialValues.onSuccessNotify,
        onFailureAction: initialValues.onFailureAction,
        onFailureGotoStep: initialValues.onFailureGotoStep,
        onFailureNotify: initialValues.onFailureNotify,
        ...config,
        // маппим ключи к тому формату, что ожидает форма
        criterionType: config.type || config.criterionType,
        expectedStage: config.targetStage || config.expectedStage,
        criteriaMessageType: config.messageType,
      });
    }
  }, [initialValues]);

  const handleStepTypeChange = (value: StepType) => {
    setStepType(value);
    setActionType(null);
    setCriterionType(null);
    form.resetFields([
      'actionType', 'criterionType', 'templateName', 'parameters',
      'conditionName', 'alertLevel', 'durationSeconds', 'expectedStage',
      'criteriaMessageType', 'timeoutSeconds',
    ]);
  };

  const configPreview = useMemo(() => {
    const values = formValues;
    const config: any = {};
    if (stepType === 'ACTION') {
      if (values.actionType) config.actionType = values.actionType;
      if (values.actionType === 'SEND_UPLINK' || values.actionType === 'SEND_GROUND') {
        if (values.templateName) config.templateName = values.templateName;
        if (values.parameters) {
          try { config.parameters = JSON.parse(values.parameters); } catch { config.parameters = values.parameters; }
        }
      }
      if (values.actionType === 'RAISE_CONDITION' || values.actionType === 'CLOSE_CONDITION') {
        if (values.conditionName) config.conditionName = values.conditionName;
        if (values.alertLevel) config.alertLevel = values.alertLevel;
      }
      if (values.actionType === 'WAIT_TIME' && values.durationSeconds) {
        config.durationSeconds = values.durationSeconds;
      }
    } else {
      if (values.criterionType) config.type = values.criterionType;
      if (values.criterionType === 'MESSAGE_RECEIVED') {
        if (values.criteriaMessageType) config.messageType = values.criteriaMessageType;
        if (values.templateName) config.templateName = values.templateName;
      }
      if (values.criterionType === 'FLIGHT_STAGE' && values.expectedStage) {
        config.targetStage = values.expectedStage;
        config.operator = 'EQUALS';
      }
      if (values.criterionType === 'TIME_COMPARISON') {
        if (values.comparisonOperator) config.operator = values.comparisonOperator;
        if (values.thresholdSeconds != null) config.thresholdSeconds = values.thresholdSeconds;
      }
      if (values.criterionType === 'CONDITION_ACTIVE' && values.conditionName) {
        config.conditionName = values.conditionName;
      }
      if (stepType === 'WAIT' && values.timeoutSeconds) {
        config.timeoutSeconds = values.timeoutSeconds;
      }
    }
    return Object.keys(config).length > 0 ? JSON.stringify(config, null, 2) : null;
  }, [formValues, stepType]);

  const handleFinish = (values: any) => {
    const configJson: any = {};

    if (stepType === 'ACTION') {
      configJson.actionType = values.actionType;
      switch (values.actionType) {
        case 'SEND_UPLINK':
        case 'SEND_GROUND':
          configJson.templateName = values.templateName;
          if (values.parameters) configJson.parameters = values.parameters;
          break;
        case 'RAISE_CONDITION':
        case 'CLOSE_CONDITION':
          configJson.conditionName = values.conditionName;
          if (values.alertLevel) configJson.alertLevel = values.alertLevel;
          break;
        case 'WAIT_TIME':
          configJson.durationSeconds = values.durationSeconds;
          break;
      }
    } else {
      // бэкенд читает 'type', не 'criterionType'
      configJson.type = values.criterionType;
      switch (values.criterionType) {
        case 'MESSAGE_RECEIVED':
          if (values.criteriaMessageType) configJson.messageType = values.criteriaMessageType;
          if (values.templateName) configJson.templateName = values.templateName;
          break;
        case 'FLIGHT_STAGE':
          // CriterionEvaluator ждёт именно targetStage + operator
          configJson.targetStage = values.expectedStage;
          configJson.operator = 'EQUALS';
          break;
        case 'TIME_COMPARISON':
          if (values.comparisonOperator) configJson.operator = values.comparisonOperator;
          if (values.thresholdSeconds) configJson.thresholdSeconds = values.thresholdSeconds;
          break;
        case 'CONDITION_ACTIVE':
          configJson.conditionName = values.conditionName;
          break;
      }
      if (stepType === 'WAIT' && values.timeoutSeconds) {
        configJson.timeoutSeconds = values.timeoutSeconds;
      }
    }

    const stepData: StepCreateRequest = {
      stepType,
      configJson: JSON.stringify(configJson),
      onSuccessAction: values.onSuccessAction,
      onSuccessGotoStep: values.onSuccessAction === 'GOTO' ? values.onSuccessGotoStep : undefined,
      onSuccessNotify: values.onSuccessNotify || false,
      onFailureAction: values.onFailureAction,
      onFailureGotoStep: values.onFailureAction === 'GOTO' ? values.onFailureGotoStep : undefined,
      onFailureNotify: values.onFailureNotify || false,
    };

    onSubmit(stepData);
  };

  return (
    <Form
      form={form}
      layout="vertical"
      onFinish={handleFinish}
      onValuesChange={(_, all) => setFormValues(all)}
    >
      <Form.Item
        name="stepType"
        label="Тип шага"
        rules={[{ required: true, message: 'Выберите тип шага' }]}
        initialValue="ACTION"
      >
        <Select onChange={handleStepTypeChange}>
          <Select.Option value="ACTION">ACTION — Действие</Select.Option>
          <Select.Option value="EVALUATE">EVALUATE — Оценка условия</Select.Option>
          <Select.Option value="WAIT">WAIT — Ожидание события</Select.Option>
        </Select>
      </Form.Item>

      {stepType === 'ACTION' && (
        <>
          <Form.Item
            name="actionType"
            label="Тип действия"
            rules={[{ required: true, message: 'Выберите тип действия' }]}
          >
            <Select onChange={setActionType}>
              <Select.Option value="SEND_UPLINK">SEND_UPLINK — Отправка наземной команды</Select.Option>
              <Select.Option value="SEND_GROUND">SEND_GROUND — Передача на землю</Select.Option>
              <Select.Option value="RAISE_CONDITION">RAISE_CONDITION — Установить условие</Select.Option>
              <Select.Option value="CLOSE_CONDITION">CLOSE_CONDITION — Снять условие</Select.Option>
              <Select.Option value="WAIT_TIME">WAIT_TIME — Пауза по времени</Select.Option>
            </Select>
          </Form.Item>

          {(actionType === 'SEND_UPLINK' || actionType === 'SEND_GROUND') && (
            <>
              <Form.Item
                name="templateName"
                label="Шаблон сообщения"
                rules={[{ required: true, message: 'Введите шаблон' }]}
              >
                <Input placeholder="Например: WEATHER_UPDATE" />
              </Form.Item>
              <Form.Item name="parameters" label="Параметры (JSON)">
                <Input.TextArea rows={3} placeholder='{"key": "value"}' />
              </Form.Item>
            </>
          )}

          {(actionType === 'RAISE_CONDITION' || actionType === 'CLOSE_CONDITION') && (
            <>
              <Form.Item
                name="conditionName"
                label="Название условия"
                rules={[{ required: true, message: 'Введите название условия' }]}
              >
                <Input placeholder="Например: WEATHER_ALERT" />
              </Form.Item>
              <Form.Item name="alertLevel" label="Уровень оповещения">
                <Select allowClear>
                  <Select.Option value="INFO">INFO — Информация</Select.Option>
                  <Select.Option value="WARNING">WARNING — Предупреждение</Select.Option>
                  <Select.Option value="CRITICAL">CRITICAL — Критический</Select.Option>
                </Select>
              </Form.Item>
            </>
          )}

          {actionType === 'WAIT_TIME' && (
            <Form.Item
              name="durationSeconds"
              label="Длительность (секунды)"
              rules={[{ required: true, message: 'Введите длительность' }]}
            >
              <InputNumber min={1} style={{ width: '100%' }} placeholder="60" />
            </Form.Item>
          )}
        </>
      )}

      {(stepType === 'EVALUATE' || stepType === 'WAIT') && (
        <>
          <Form.Item
            name="criterionType"
            label="Тип критерия"
            rules={[{ required: true, message: 'Выберите тип критерия' }]}
          >
            <Select onChange={setCriterionType}>
              <Select.Option value="MESSAGE_RECEIVED">MESSAGE_RECEIVED — Получено сообщение</Select.Option>
              <Select.Option value="FLIGHT_STAGE">FLIGHT_STAGE — Фаза полёта</Select.Option>
              <Select.Option value="POSITION_REPORTED">POSITION_REPORTED — Доклад о позиции</Select.Option>
              <Select.Option value="TIME_COMPARISON">TIME_COMPARISON — Сравнение времени</Select.Option>
              <Select.Option value="CONDITION_ACTIVE">CONDITION_ACTIVE — Условие активно</Select.Option>
              <Select.Option value="COMPOUND">COMPOUND — Составное условие</Select.Option>
            </Select>
          </Form.Item>

          {criterionType === 'MESSAGE_RECEIVED' && (
            <>
              <Form.Item
                name="criteriaMessageType"
                label="Тип сообщения"
                rules={[{ required: true, message: 'Выберите тип сообщения' }]}
              >
                <Select>
                  <Select.Option value="DOWNLINK">DOWNLINK — Борт → земля</Select.Option>
                  <Select.Option value="UPLINK">UPLINK — Земля → борт</Select.Option>
                  <Select.Option value="GROUND">GROUND — Наземная</Select.Option>
                </Select>
              </Form.Item>
              <Form.Item name="templateName" label="Шаблон сообщения">
                <Input placeholder="Оставьте пустым — для любого сообщения" />
              </Form.Item>
            </>
          )}

          {criterionType === 'FLIGHT_STAGE' && (
            <Form.Item
              name="expectedStage"
              label="Ожидаемая фаза полёта"
              rules={[{ required: true, message: 'Выберите фазу' }]}
            >
              <Select>
                <Select.Option value="INIT">INIT — Начальная</Select.Option>
                <Select.Option value="OUT">OUT — Выруливание</Select.Option>
                <Select.Option value="OFF">OFF — Взлёт</Select.Option>
                <Select.Option value="ON">ON — Посадка</Select.Option>
                <Select.Option value="IN">IN — Заруливание</Select.Option>
              </Select>
            </Form.Item>
          )}

          {criterionType === 'TIME_COMPARISON' && (
            <>
              <Form.Item name="comparisonOperator" label="Оператор сравнения">
                <Select>
                  <Select.Option value="GREATER_THAN">{'Больше (>)'}</Select.Option>
                  <Select.Option value="LESS_THAN">Меньше (&lt;)</Select.Option>
                  <Select.Option value="GREATER_OR_EQUAL">Больше или равно (≥)</Select.Option>
                  <Select.Option value="LESS_OR_EQUAL">Меньше или равно (≤)</Select.Option>
                </Select>
              </Form.Item>
              <Form.Item name="thresholdSeconds" label="Порог (секунды)">
                <InputNumber min={0} style={{ width: '100%' }} />
              </Form.Item>
            </>
          )}

          {criterionType === 'CONDITION_ACTIVE' && (
            <Form.Item
              name="conditionName"
              label="Название условия"
              rules={[{ required: true, message: 'Введите название условия' }]}
            >
              <Input placeholder="Например: WEATHER_ALERT" />
            </Form.Item>
          )}

          {stepType === 'WAIT' && (
            <Form.Item
              name="timeoutSeconds"
              label="Тайм-аут ожидания (секунды)"
              rules={[{ required: true, message: 'Введите тайм-аут' }]}
            >
              <InputNumber min={1} style={{ width: '100%' }} placeholder="300" />
            </Form.Item>
          )}
        </>
      )}

      <Divider>Переходы</Divider>

      <div style={{ display: 'flex', gap: '16px' }}>
        <div style={{ flex: 1 }}>
          <h4 style={{ color: '#52c41a', marginTop: 0 }}>При успехе</h4>
          <Form.Item
            name="onSuccessAction"
            label="Действие"
            rules={[{ required: true }]}
            initialValue="CONTINUE"
          >
            <Select onChange={setOnSuccessAction}>
              <Select.Option value="CONTINUE">CONTINUE — Продолжить</Select.Option>
              <Select.Option value="GOTO">GOTO — Перейти к шагу</Select.Option>
              <Select.Option value="END">END — Завершить</Select.Option>
              <Select.Option value="ABORT">ABORT — Прервать</Select.Option>
            </Select>
          </Form.Item>
          {onSuccessAction === 'GOTO' && (
            <Form.Item
              name="onSuccessGotoStep"
              label="Номер шага"
              rules={[{ required: true, message: 'Введите номер шага' }]}
            >
              <InputNumber min={0} style={{ width: '100%' }} />
            </Form.Item>
          )}
          <Form.Item name="onSuccessNotify" valuePropName="checked">
            <Checkbox>Уведомить при успехе</Checkbox>
          </Form.Item>
        </div>

        <div style={{ flex: 1 }}>
          <h4 style={{ color: '#ff4d4f', marginTop: 0 }}>При ошибке</h4>
          <Form.Item
            name="onFailureAction"
            label="Действие"
            rules={[{ required: true }]}
            initialValue="ABORT"
          >
            <Select onChange={setOnFailureAction}>
              <Select.Option value="CONTINUE">CONTINUE — Продолжить</Select.Option>
              <Select.Option value="GOTO">GOTO — Перейти к шагу</Select.Option>
              <Select.Option value="END">END — Завершить</Select.Option>
              <Select.Option value="ABORT">ABORT — Прервать</Select.Option>
            </Select>
          </Form.Item>
          {onFailureAction === 'GOTO' && (
            <Form.Item
              name="onFailureGotoStep"
              label="Номер шага"
              rules={[{ required: true, message: 'Введите номер шага' }]}
            >
              <InputNumber min={0} style={{ width: '100%' }} />
            </Form.Item>
          )}
          <Form.Item name="onFailureNotify" valuePropName="checked">
            <Checkbox>Уведомить при ошибке</Checkbox>
          </Form.Item>
        </div>
      </div>

      {configPreview && (
        <>
          <Divider>Превью конфига шага</Divider>
          <div
            style={{
              background: previewBg,
              border: `1px solid ${previewBorder}`,
              borderRadius: 8,
              padding: '12px 14px',
              marginBottom: 16,
            }}
          >
            <Text style={{ fontSize: 11, color: '#848d97', display: 'block', marginBottom: 6 }}>
              configJson (будет сохранено в БД):
            </Text>
            <pre style={{ margin: 0, fontSize: 12, color: '#52c41a', lineHeight: 1.6 }}>
              {configPreview}
            </pre>
          </div>
        </>
      )}

      <Form.Item>
        <Space>
          <Button type="primary" htmlType="submit">
            {initialValues ? 'Сохранить' : 'Добавить'} шаг
          </Button>
          <Button onClick={onCancel}>Отмена</Button>
        </Space>
      </Form.Item>
    </Form>
  );
};
