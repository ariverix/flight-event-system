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
import { useEditorI18n } from '../../i18n/useEditorI18n';

const { Text } = Typography;

interface StepFormProps {
  onSubmit: (data: StepCreateRequest) => void;
  onCancel: () => void;
  initialValues?: StepResponse | null;
}

export const StepForm: React.FC<StepFormProps> = ({ onSubmit, onCancel, initialValues }) => {
  const d = useEditorI18n();
  const [form] = Form.useForm();
  const [stepType, setStepType] = useState<StepType>('ACTION');
  const [actionType, setActionType] = useState<ActionType | null>(null);
  const [criterionType, setCriterionType] = useState<CriterionType | null>(null);
  const [onSuccessAction, setOnSuccessAction] = useState<TransitionAction>('CONTINUE');
  const [onFailureAction, setOnFailureAction] = useState<TransitionAction>('ABORT');
  const [formValues, setFormValues] = useState<any>({});
  const { isDark } = useTheme();
  const previewBg = isDark ? '#1e1e1e' : '#f5f5f7';
  const previewBorder = isDark ? 'rgba(255,255,255,0.12)' : 'rgba(0,0,0,0.10)';

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
        label={d.nodeStepTypeLabel}
        rules={[{ required: true, message: d.stepTypePick }]}
        initialValue="ACTION"
      >
        <Select onChange={handleStepTypeChange}>
          <Select.Option value="ACTION">ACTION — {d.stepTypeAction}</Select.Option>
          <Select.Option value="EVALUATE">EVALUATE — {d.stepTypeEvaluate}</Select.Option>
          <Select.Option value="WAIT">WAIT — {d.stepTypeWait}</Select.Option>
        </Select>
      </Form.Item>

      {stepType === 'ACTION' && (
        <>
          <Form.Item
            name="actionType"
            label={d.actionTypeLabel}
            rules={[{ required: true, message: d.actionTypePick }]}
          >
            <Select onChange={setActionType}>
              <Select.Option value="SEND_UPLINK">SEND_UPLINK — {d.configLabels.SEND_UPLINK}</Select.Option>
              <Select.Option value="SEND_GROUND">SEND_GROUND — {d.configLabels.SEND_GROUND}</Select.Option>
              <Select.Option value="RAISE_CONDITION">RAISE_CONDITION — {d.configLabels.RAISE_CONDITION}</Select.Option>
              <Select.Option value="CLOSE_CONDITION">CLOSE_CONDITION — {d.configLabels.CLOSE_CONDITION}</Select.Option>
              <Select.Option value="WAIT_TIME">WAIT_TIME — {d.configLabels.WAIT_TIME}</Select.Option>
            </Select>
          </Form.Item>

          {(actionType === 'SEND_UPLINK' || actionType === 'SEND_GROUND') && (
            <>
              <Form.Item
                name="templateName"
                label={d.templateLabel}
                rules={[{ required: true, message: d.seqStepTemplateRequired }]}
              >
                <Input placeholder={d.seqStepTemplatePlaceholder} />
              </Form.Item>
              <Form.Item name="parameters" label={d.seqStepParamsLabel}>
                <Input.TextArea rows={3} placeholder='{"key": "value"}' />
              </Form.Item>
            </>
          )}

          {(actionType === 'RAISE_CONDITION' || actionType === 'CLOSE_CONDITION') && (
            <>
              <Form.Item
                name="conditionName"
                label={d.conditionNameLabel}
                rules={[{ required: true, message: d.validationErrors.errConditionName }]}
              >
                <Input placeholder={d.conditionNamePlaceholder} />
              </Form.Item>
              <Form.Item name="alertLevel" label={d.alertLevelLabel}>
                <Select allowClear>
                  {Object.entries(d.alertLevels).map(([value, label]) => (
                    <Select.Option key={value} value={value}>
                      {value} — {label}
                    </Select.Option>
                  ))}
                </Select>
              </Form.Item>
            </>
          )}

          {actionType === 'WAIT_TIME' && (
            <Form.Item
              name="durationSeconds"
              label={d.seqStepDurationSecondsLabel}
              rules={[{ required: true, message: d.validationErrors.errDuration }]}
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
            label={d.criterionTypeLabel}
            rules={[{ required: true, message: d.seqStepCriterionTypeRequired }]}
          >
            <Select onChange={setCriterionType}>
              <Select.Option value="MESSAGE_RECEIVED">MESSAGE_RECEIVED — {d.configLabels.MESSAGE_RECEIVED}</Select.Option>
              <Select.Option value="FLIGHT_STAGE">FLIGHT_STAGE — {d.configLabels.FLIGHT_STAGE}</Select.Option>
              <Select.Option value="POSITION_REPORTED">POSITION_REPORTED — {d.configLabels.POSITION_REPORTED}</Select.Option>
              <Select.Option value="TIME_COMPARISON">TIME_COMPARISON — {d.configLabels.TIME_COMPARISON}</Select.Option>
              <Select.Option value="CONDITION_ACTIVE">CONDITION_ACTIVE — {d.configLabels.CONDITION_ACTIVE}</Select.Option>
              <Select.Option value="COMPOUND">COMPOUND — {d.configLabels.COMPOUND}</Select.Option>
            </Select>
          </Form.Item>

          {criterionType === 'MESSAGE_RECEIVED' && (
            <>
              <Form.Item
                name="criteriaMessageType"
                label={d.msgTypeFilterLabel}
                rules={[{ required: true, message: d.validationErrors.errMessageDirection }]}
              >
                <Select>
                  <Select.Option value="DOWNLINK">{d.msgDirections.DOWNLINK}</Select.Option>
                  <Select.Option value="UPLINK">{d.msgDirections.UPLINK}</Select.Option>
                  <Select.Option value="GROUND">{d.msgDirections.GROUND}</Select.Option>
                </Select>
              </Form.Item>
              <Form.Item name="templateName" label={d.templateLabel}>
                <Input placeholder={d.msgTemplateNamePh} />
              </Form.Item>
            </>
          )}

          {criterionType === 'FLIGHT_STAGE' && (
            <Form.Item
              name="expectedStage"
              label={d.targetStageLabel}
              rules={[{ required: true, message: d.validationErrors.errFlightStage }]}
            >
              <Select>
                <Select.Option value="INIT">{d.flightStages.INIT}</Select.Option>
                <Select.Option value="OUT">{d.flightStages.OUT}</Select.Option>
                <Select.Option value="OFF">{d.flightStages.OFF}</Select.Option>
                <Select.Option value="ON">{d.flightStages.ON}</Select.Option>
                <Select.Option value="IN">{d.flightStages.IN}</Select.Option>
              </Select>
            </Form.Item>
          )}

          {criterionType === 'TIME_COMPARISON' && (
            <>
              <Form.Item name="comparisonOperator" label={d.stageOperatorLabel}>
                <Select>
                  <Select.Option value="GREATER_THAN">{d.stageOperators.GREATER_THAN}</Select.Option>
                  <Select.Option value="LESS_THAN">{d.stageOperators.LESS_THAN}</Select.Option>
                  <Select.Option value="GREATER_OR_EQUAL">{d.stageOperators.GREATER_OR_EQUAL}</Select.Option>
                  <Select.Option value="LESS_OR_EQUAL">{d.stageOperators.LESS_OR_EQUAL}</Select.Option>
                </Select>
              </Form.Item>
              <Form.Item name="thresholdSeconds" label={d.seqStepThresholdSecondsLabel}>
                <InputNumber min={0} style={{ width: '100%' }} />
              </Form.Item>
            </>
          )}

          {criterionType === 'CONDITION_ACTIVE' && (
            <Form.Item
              name="conditionName"
              label={d.conditionNameLabel}
              rules={[{ required: true, message: d.validationErrors.errConditionName }]}
            >
              <Input placeholder={d.conditionNamePlaceholder} />
            </Form.Item>
          )}

          {stepType === 'WAIT' && (
            <Form.Item
              name="timeoutSeconds"
              label={d.timeoutSecondsLabel}
              rules={[{ required: true, message: d.seqStepTimeoutRequired }]}
            >
              <InputNumber min={1} style={{ width: '100%' }} placeholder="300" />
            </Form.Item>
          )}
        </>
      )}

      <Divider>{d.transitionsTitle}</Divider>

      <div style={{ display: 'flex', gap: '16px' }}>
        <div style={{ flex: 1 }}>
          <h4 style={{ color: 'var(--accent-green)', marginTop: 0 }}>{d.onSuccessTitle}</h4>
          <Form.Item
            name="onSuccessAction"
            label={d.decisionActionLabel}
            rules={[{ required: true }]}
            initialValue="CONTINUE"
          >
            <Select onChange={setOnSuccessAction}>
              <Select.Option value="CONTINUE">CONTINUE — {d.execTransitions.CONTINUE}</Select.Option>
              <Select.Option value="GOTO">GOTO — {d.execTransitions.GOTO}</Select.Option>
              <Select.Option value="END">END — {d.execTransitions.END}</Select.Option>
              <Select.Option value="ABORT">ABORT — {d.execTransitions.ABORT}</Select.Option>
            </Select>
          </Form.Item>
          {onSuccessAction === 'GOTO' && (
            <Form.Item
              name="onSuccessGotoStep"
              label={d.decisionGotoStepLabel}
              rules={[{ required: true, message: d.seqStepGotoStepRequired }]}
            >
              <InputNumber min={0} style={{ width: '100%' }} />
            </Form.Item>
          )}
          <Form.Item name="onSuccessNotify" valuePropName="checked">
            <Checkbox>{d.notifyOnSuccess}</Checkbox>
          </Form.Item>
        </div>

        <div style={{ flex: 1 }}>
          <h4 style={{ color: 'var(--accent-red)', marginTop: 0 }}>{d.onFailureTitle}</h4>
          <Form.Item
            name="onFailureAction"
            label={d.decisionActionLabel}
            rules={[{ required: true }]}
            initialValue="ABORT"
          >
            <Select onChange={setOnFailureAction}>
              <Select.Option value="CONTINUE">CONTINUE — {d.execTransitions.CONTINUE}</Select.Option>
              <Select.Option value="GOTO">GOTO — {d.execTransitions.GOTO}</Select.Option>
              <Select.Option value="END">END — {d.execTransitions.END}</Select.Option>
              <Select.Option value="ABORT">ABORT — {d.execTransitions.ABORT}</Select.Option>
            </Select>
          </Form.Item>
          {onFailureAction === 'GOTO' && (
            <Form.Item
              name="onFailureGotoStep"
              label={d.decisionGotoStepLabel}
              rules={[{ required: true, message: d.seqStepGotoStepRequired }]}
            >
              <InputNumber min={0} style={{ width: '100%' }} />
            </Form.Item>
          )}
          <Form.Item name="onFailureNotify" valuePropName="checked">
            <Checkbox>{d.notifyOnFailure}</Checkbox>
          </Form.Item>
        </div>
      </div>

      {configPreview && (
        <>
          <Divider>{d.seqStepConfigPreviewDivider}</Divider>
          <div
            style={{
              background: previewBg,
              border: `1px solid ${previewBorder}`,
              borderRadius: 8,
              padding: '12px 14px',
              marginBottom: 16,
            }}
          >
            <Text style={{ fontSize: 11, color: isDark ? 'rgba(255,255,255,0.55)' : '#6e6e73', display: 'block', marginBottom: 6 }}>
              {d.seqStepConfigPreviewNote}
            </Text>
            <pre style={{ margin: 0, fontSize: 12, color: isDark ? '#30d158' : '#15803d', lineHeight: 1.6 }}>
              {configPreview}
            </pre>
          </div>
        </>
      )}

      <Form.Item>
        <Space>
          <Button type="primary" htmlType="submit">
            {initialValues ? d.submitSaveStep : d.submitAddStep}
          </Button>
          <Button onClick={onCancel}>{d.cancel}</Button>
        </Space>
      </Form.Item>
    </Form>
  );
};
