import React, { useState, useEffect } from 'react';
import { Form, Select, Input, InputNumber, Button, Space, Checkbox, Divider } from 'antd';
import { StepCreateRequest, StepResponse, StepType, ActionType, CriterionType, TransitionAction } from '../../types/sequence';

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

  useEffect(() => {
    if (initialValues) {
      const config = JSON.parse(initialValues.configJson);
      setStepType(initialValues.stepType);
      setOnSuccessAction(initialValues.onSuccessAction);
      setOnFailureAction(initialValues.onFailureAction);

      if (initialValues.stepType === 'ACTION') {
        setActionType(config.actionType);
      } else {
        setCriterionType(config.criterionType);
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
      });
    }
  }, [initialValues]);

  const handleStepTypeChange = (value: StepType) => {
    setStepType(value);
    setActionType(null);
    setCriterionType(null);
    form.resetFields(['actionType', 'criterionType', 'templateName', 'parameters', 'conditionName', 'alertLevel', 'durationSeconds', 'expectedStage', 'timeoutSeconds']);
  };

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
      configJson.criterionType = values.criterionType;

      switch (values.criterionType) {
        case 'MESSAGE_RECEIVED':
          if (values.templateName) configJson.templateName = values.templateName;
          break;
        case 'FLIGHT_STAGE':
          configJson.expectedStage = values.expectedStage;
          break;
        case 'TIME_COMPARISON':
          if (values.comparisonOperator) configJson.comparisonOperator = values.comparisonOperator;
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
    <Form form={form} layout="vertical" onFinish={handleFinish}>
      <Form.Item
        name="stepType"
        label="Step Type"
        rules={[{ required: true, message: 'Please select step type!' }]}
        initialValue="ACTION"
      >
        <Select onChange={handleStepTypeChange}>
          <Select.Option value="ACTION">ACTION</Select.Option>
          <Select.Option value="EVALUATE">EVALUATE</Select.Option>
          <Select.Option value="WAIT">WAIT</Select.Option>
        </Select>
      </Form.Item>

      {stepType === 'ACTION' && (
        <>
          <Form.Item
            name="actionType"
            label="Action Type"
            rules={[{ required: true, message: 'Please select action type!' }]}
          >
            <Select onChange={setActionType}>
              <Select.Option value="SEND_UPLINK">SEND_UPLINK</Select.Option>
              <Select.Option value="SEND_GROUND">SEND_GROUND</Select.Option>
              <Select.Option value="RAISE_CONDITION">RAISE_CONDITION</Select.Option>
              <Select.Option value="CLOSE_CONDITION">CLOSE_CONDITION</Select.Option>
              <Select.Option value="WAIT_TIME">WAIT_TIME</Select.Option>
            </Select>
          </Form.Item>

          {(actionType === 'SEND_UPLINK' || actionType === 'SEND_GROUND') && (
            <>
              <Form.Item
                name="templateName"
                label="Template Name"
                rules={[{ required: true, message: 'Please input template name!' }]}
              >
                <Input placeholder="e.g., WEATHER_UPDATE" />
              </Form.Item>
              <Form.Item name="parameters" label="Parameters (JSON)">
                <Input.TextArea rows={3} placeholder='{"key": "value"}' />
              </Form.Item>
            </>
          )}

          {(actionType === 'RAISE_CONDITION' || actionType === 'CLOSE_CONDITION') && (
            <>
              <Form.Item
                name="conditionName"
                label="Condition Name"
                rules={[{ required: true, message: 'Please input condition name!' }]}
              >
                <Input placeholder="e.g., WEATHER_ALERT" />
              </Form.Item>
              <Form.Item name="alertLevel" label="Alert Level">
                <Select allowClear>
                  <Select.Option value="INFO">INFO</Select.Option>
                  <Select.Option value="WARNING">WARNING</Select.Option>
                  <Select.Option value="CRITICAL">CRITICAL</Select.Option>
                </Select>
              </Form.Item>
            </>
          )}

          {actionType === 'WAIT_TIME' && (
            <Form.Item
              name="durationSeconds"
              label="Duration (seconds)"
              rules={[{ required: true, message: 'Please input duration!' }]}
            >
              <InputNumber min={1} style={{ width: '100%' }} />
            </Form.Item>
          )}
        </>
      )}

      {(stepType === 'EVALUATE' || stepType === 'WAIT') && (
        <>
          <Form.Item
            name="criterionType"
            label="Criterion Type"
            rules={[{ required: true, message: 'Please select criterion type!' }]}
          >
            <Select onChange={setCriterionType}>
              <Select.Option value="MESSAGE_RECEIVED">MESSAGE_RECEIVED</Select.Option>
              <Select.Option value="FLIGHT_STAGE">FLIGHT_STAGE</Select.Option>
              <Select.Option value="POSITION_REPORTED">POSITION_REPORTED</Select.Option>
              <Select.Option value="TIME_COMPARISON">TIME_COMPARISON</Select.Option>
              <Select.Option value="CONDITION_ACTIVE">CONDITION_ACTIVE</Select.Option>
              <Select.Option value="COMPOUND">COMPOUND</Select.Option>
            </Select>
          </Form.Item>

          {criterionType === 'MESSAGE_RECEIVED' && (
            <Form.Item name="templateName" label="Template Name">
              <Input placeholder="Leave empty for any message" />
            </Form.Item>
          )}

          {criterionType === 'FLIGHT_STAGE' && (
            <Form.Item
              name="expectedStage"
              label="Expected Stage"
              rules={[{ required: true, message: 'Please select expected stage!' }]}
            >
              <Select>
                <Select.Option value="INIT">INIT</Select.Option>
                <Select.Option value="OUT">OUT</Select.Option>
                <Select.Option value="OFF">OFF</Select.Option>
                <Select.Option value="ON">ON</Select.Option>
                <Select.Option value="IN">IN</Select.Option>
              </Select>
            </Form.Item>
          )}

          {criterionType === 'TIME_COMPARISON' && (
            <>
              <Form.Item name="comparisonOperator" label="Comparison Operator">
                <Select>
                  <Select.Option value="GREATER_THAN">GREATER_THAN</Select.Option>
                  <Select.Option value="LESS_THAN">LESS_THAN</Select.Option>
                  <Select.Option value="GREATER_OR_EQUAL">GREATER_OR_EQUAL</Select.Option>
                  <Select.Option value="LESS_OR_EQUAL">LESS_OR_EQUAL</Select.Option>
                </Select>
              </Form.Item>
              <Form.Item name="thresholdSeconds" label="Threshold (seconds)">
                <InputNumber min={0} style={{ width: '100%' }} />
              </Form.Item>
            </>
          )}

          {criterionType === 'CONDITION_ACTIVE' && (
            <Form.Item
              name="conditionName"
              label="Condition Name"
              rules={[{ required: true, message: 'Please input condition name!' }]}
            >
              <Input placeholder="e.g., WEATHER_ALERT" />
            </Form.Item>
          )}

          {stepType === 'WAIT' && (
            <Form.Item
              name="timeoutSeconds"
              label="Timeout (seconds)"
              rules={[{ required: true, message: 'Please input timeout!' }]}
            >
              <InputNumber min={1} style={{ width: '100%' }} />
            </Form.Item>
          )}
        </>
      )}

      <Divider>Transitions</Divider>

      <div style={{ display: 'flex', gap: '16px' }}>
        <div style={{ flex: 1 }}>
          <h4>On Success</h4>
          <Form.Item
            name="onSuccessAction"
            label="Action"
            rules={[{ required: true }]}
            initialValue="CONTINUE"
          >
            <Select onChange={setOnSuccessAction}>
              <Select.Option value="CONTINUE">CONTINUE</Select.Option>
              <Select.Option value="GOTO">GOTO</Select.Option>
              <Select.Option value="END">END</Select.Option>
              <Select.Option value="ABORT">ABORT</Select.Option>
            </Select>
          </Form.Item>
          {onSuccessAction === 'GOTO' && (
            <Form.Item
              name="onSuccessGotoStep"
              label="Go to Step"
              rules={[{ required: true, message: 'Please input step number!' }]}
            >
              <InputNumber min={0} style={{ width: '100%' }} />
            </Form.Item>
          )}
          <Form.Item name="onSuccessNotify" valuePropName="checked">
            <Checkbox>Notify on success</Checkbox>
          </Form.Item>
        </div>

        <div style={{ flex: 1 }}>
          <h4>On Failure</h4>
          <Form.Item
            name="onFailureAction"
            label="Action"
            rules={[{ required: true }]}
            initialValue="ABORT"
          >
            <Select onChange={setOnFailureAction}>
              <Select.Option value="CONTINUE">CONTINUE</Select.Option>
              <Select.Option value="GOTO">GOTO</Select.Option>
              <Select.Option value="END">END</Select.Option>
              <Select.Option value="ABORT">ABORT</Select.Option>
            </Select>
          </Form.Item>
          {onFailureAction === 'GOTO' && (
            <Form.Item
              name="onFailureGotoStep"
              label="Go to Step"
              rules={[{ required: true, message: 'Please input step number!' }]}
            >
              <InputNumber min={0} style={{ width: '100%' }} />
            </Form.Item>
          )}
          <Form.Item name="onFailureNotify" valuePropName="checked">
            <Checkbox>Notify on failure</Checkbox>
          </Form.Item>
        </div>
      </div>

      <Form.Item>
        <Space>
          <Button type="primary" htmlType="submit">
            {initialValues ? 'Update' : 'Add'} Step
          </Button>
          <Button onClick={onCancel}>Cancel</Button>
        </Space>
      </Form.Item>
    </Form>
  );
};
