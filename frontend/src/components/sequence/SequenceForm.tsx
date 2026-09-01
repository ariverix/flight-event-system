import React, { useState, useEffect, useCallback } from 'react';
import { Form, Input, Button, Space, Card, Modal, Table, Tag, Tooltip } from 'antd';
import { useNotification } from '../../hooks/useNotification';
import { useNavigate, useParams } from 'react-router-dom';
import { PlusOutlined, DeleteOutlined, EditOutlined, ArrowUpOutlined, ArrowDownOutlined, ApartmentOutlined } from '@ant-design/icons';
import { sequenceApi } from '../../api/sequenceApi';
import { SequenceResponse, StepResponse, StepCreateRequest } from '../../types/sequence';
import { CriteriaEditor } from './CriteriaEditor';
import { StepForm } from './StepForm';
import { SequenceFlow } from './SequenceFlow';
import { useTheme } from '../../context/ThemeContext';
import { useAuth } from '../../hooks/useAuth';
import { useEditorI18n } from '../../i18n/useEditorI18n';

const STEP_TYPE_COLOR: Record<string, string> = {
  ACTION:   'blue',
  EVALUATE: 'gold',
  WAIT:     'purple',
};

export const SequenceForm: React.FC = () => {
  const notification = useNotification();
  const d = useEditorI18n();
  const STEP_TYPE_LABEL: Record<string, string> = {
    ACTION:   d.stepTypeAction,
    EVALUATE: d.stepTypeEvaluate,
    WAIT:     d.stepTypeWait,
  };
  const [form] = Form.useForm();
  const [loading, setLoading] = useState(false);
  const [sequence, setSequence] = useState<SequenceResponse | null>(null);
  const [isStepModalOpen, setIsStepModalOpen] = useState(false);
  const [editingStep, setEditingStep] = useState<StepResponse | null>(null);
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const isEditMode = id && id !== 'new';
  const { isAdmin } = useAuth();
  const { isDark } = useTheme();
  const c = isDark
    ? { borderSecondary: 'rgba(255,255,255,0.09)', text: '#f5f5f7' }
    : { borderSecondary: 'rgba(0,0,0,0.08)', text: '#1d1d1f' };

  const loadSequence = useCallback(async () => {
    if (!id || id === 'new') return;
    setLoading(true);
    try {
      const data = await sequenceApi.getSequenceById(parseInt(id));
      setSequence(data);
      form.setFieldsValue({
        name: data.name,
        description: data.description,
        startCriteriaJson: data.startCriteriaJson || '',
        stopCriteriaJson: data.stopCriteriaJson || '',
      });
    } catch (error: any) {
      notification.error({
        message: d.seqFormLoadError,
        description: error.response?.data?.message || error.message,
      });
      navigate('/sequences');
    } finally {
      setLoading(false);
    }
  }, [id, navigate, form]);

  useEffect(() => {
    if (isEditMode) loadSequence();
  }, [isEditMode, loadSequence]);

  const handleSubmit = async (values: any) => {
    setLoading(true);
    try {
      const request = {
        name: values.name,
        description: values.description,
        startCriteriaJson: values.startCriteriaJson?.trim() || undefined,
        stopCriteriaJson: values.stopCriteriaJson?.trim() || undefined,
      };

      if (isEditMode && id) {
        await sequenceApi.updateSequence(parseInt(id), request);
        notification.success({ message: d.seqFormUpdateSuccess });
        await loadSequence();
      } else {
        const newSeq = await sequenceApi.createSequence(request);
        notification.success({ message: d.seqFormCreateSuccess });
        navigate(`/sequences/${newSeq.id}`);
      }
    } catch (error: any) {
      notification.error({
        message: d.seqFormSaveError,
        description: error.response?.data?.message || error.message,
      });
    } finally {
      setLoading(false);
    }
  };

  const handleStepSubmit = async (stepData: StepCreateRequest) => {
    if (!sequence) return;
    try {
      if (editingStep) {
        await sequenceApi.updateStep(sequence.id, editingStep.id, stepData);
        notification.success({ message: d.seqFormStepUpdateSuccess });
      } else {
        await sequenceApi.addStep(sequence.id, stepData);
        notification.success({ message: d.seqFormStepAddSuccess });
      }
      setIsStepModalOpen(false);
      setEditingStep(null);
      void loadSequence();
    } catch (error: any) {
      notification.error({
        message: d.seqFormStepSaveError,
        description: error.response?.data?.message || error.message,
      });
    }
  };

  const handleMoveStep = async (stepId: number, direction: 'up' | 'down') => {
    if (!sequence) return;
    const steps = [...sequence.steps].sort((a, b) => a.orderIndex - b.orderIndex);
    const idx = steps.findIndex(s => s.id === stepId);
    const swapIdx = direction === 'up' ? idx - 1 : idx + 1;
    if (swapIdx < 0 || swapIdx >= steps.length) return;
    [steps[idx], steps[swapIdx]] = [steps[swapIdx], steps[idx]];
    try {
      await sequenceApi.reorderSteps(sequence.id, steps.map(s => s.id));
      void loadSequence();
    } catch (error: any) {
      notification.error({
        message: d.seqFormReorderError,
        description: error.response?.data?.message || error.message,
      });
    }
  };

  const handleDeleteStep = async (stepId: number) => {
    if (!sequence) return;
    try {
      await sequenceApi.deleteStep(sequence.id, stepId);
      notification.success({ message: d.seqFormStepDeleteSuccess });
      void loadSequence();
    } catch (error: any) {
      notification.error({
        message: d.seqFormStepDeleteError,
        description: error.response?.data?.message || error.message,
      });
    }
  };

  const stepColumns = [
    { title: '№', dataIndex: 'orderIndex', key: 'orderIndex', width: 60 },
    {
      title: d.tlDetailsTypeLabel,
      dataIndex: 'stepType',
      key: 'stepType',
      render: (type: string) => (
        <Tag color={STEP_TYPE_COLOR[type] ?? 'blue'}>{STEP_TYPE_LABEL[type] ?? type}</Tag>
      ),
    },
    {
      title: d.seqFormConfigCol,
      dataIndex: 'configJson',
      key: 'configJson',
      ellipsis: true,
      render: (config: string) => {
        try {
          const parsed = JSON.parse(config);
          const label = parsed.actionType || parsed.type || parsed.criterionType || '—';
          return (
            <Tooltip title={<pre style={{ margin: 0, fontSize: 11 }}>{JSON.stringify(parsed, null, 2)}</pre>} placement="topLeft">
              <span>{label}</span>
            </Tooltip>
          );
        } catch {
          return '—';
        }
      },
    },
    {
      title: d.seqFormOnSuccessCol,
      dataIndex: 'onSuccessAction',
      key: 'onSuccessAction',
      render: (action: string, record: StepResponse) => (
        <span>
          {action}
          {action === 'GOTO' && record.onSuccessGotoStep != null && ` → ${record.onSuccessGotoStep}`}
        </span>
      ),
    },
    {
      title: d.seqFormOnFailureCol,
      dataIndex: 'onFailureAction',
      key: 'onFailureAction',
      render: (action: string, record: StepResponse) => (
        <span>
          {action}
          {action === 'GOTO' && record.onFailureGotoStep != null && ` → ${record.onFailureGotoStep}`}
        </span>
      ),
    },
    {
      title: d.seqColActions,
      key: 'actions',
      width: 280,
      render: (_: any, record: StepResponse) => {
        const steps = sequence?.steps ?? [];
        const sortedSteps = [...steps].sort((a, b) => a.orderIndex - b.orderIndex);
        const idx = sortedSteps.findIndex(s => s.id === record.id);
        return (
          <Space size={4} wrap>
            {isAdmin && (
              <Button
                size="small"
                icon={<ArrowUpOutlined />}
                disabled={idx === 0}
                onClick={() => handleMoveStep(record.id, 'up')}
                title={d.seqFormMoveUpTooltip}
              />
            )}
            {isAdmin && (
              <Button
                size="small"
                icon={<ArrowDownOutlined />}
                disabled={idx === sortedSteps.length - 1}
                onClick={() => handleMoveStep(record.id, 'down')}
                title={d.seqFormMoveDownTooltip}
              />
            )}
            {isAdmin && (
              <Button
                size="small"
                icon={<EditOutlined />}
                onClick={() => { setEditingStep(record); setIsStepModalOpen(true); }}
              >
                {d.editStep}
              </Button>
            )}
            {isAdmin && (
              <Button
                size="small"
                danger
                icon={<DeleteOutlined />}
                onClick={() => handleDeleteStep(record.id)}
              >
                {d.deleteStep}
              </Button>
            )}
          </Space>
        );
      },
    },
  ];

  return (
    <div className="fade-in-up">
      <h2 className="page-title" style={{ marginBottom: 20 }}>
        {isEditMode ? d.seqFormTitleEdit : d.seqFormTitleCreate}
      </h2>

      <Card style={{ marginBottom: 16, borderColor: c.borderSecondary }}>
        <Form form={form} layout="vertical" onFinish={handleSubmit}>
          <Form.Item
            name="name"
            label={d.seqFormNameLabel}
            rules={[{ required: true, message: d.seqFormNameRequired }]}
          >
            <Input placeholder={d.seqFormNamePlaceholder} />
          </Form.Item>

          <Form.Item
            name="description"
            label={d.seqColDescription}
            rules={[{ required: true, message: d.seqFormDescRequired }]}
          >
            <Input.TextArea rows={3} placeholder={d.seqFormDescPlaceholder} />
          </Form.Item>

          <Form.Item name="startCriteriaJson" label={d.seqFormStartCriteriaLabel}>
            <CriteriaEditor />
          </Form.Item>

          <Form.Item name="stopCriteriaJson" label={d.seqFormStopCriteriaLabel}>
            <CriteriaEditor />
          </Form.Item>

          <Form.Item>
            <Space>
              {isAdmin && (
                <Button type="primary" htmlType="submit" loading={loading}>
                  {isEditMode ? d.seqFormSubmitSave : d.seqFormTitleCreate}
                </Button>
              )}
              {isEditMode && id && (
                <Button
                  icon={<ApartmentOutlined />}
                  onClick={() => navigate(`/sequences/${id}/editor`)}
                >
                  {d.openEditor}
                </Button>
              )}
              <Button onClick={() => navigate('/sequences')}>
                {isAdmin ? d.usersCancelBtn : d.seqFormBackBtn}
              </Button>
            </Space>
          </Form.Item>
        </Form>
      </Card>

      {sequence && (
        <>
          <Card
            title={<span style={{ color: c.text }}>{d.seqFormStepsCardTitle}</span>}
            style={{ marginBottom: 16, borderColor: c.borderSecondary }}
          >
            {isAdmin && (
              <Button
                type="dashed"
                icon={<PlusOutlined />}
                onClick={() => { setEditingStep(null); setIsStepModalOpen(true); }}
                style={{ marginBottom: 16, width: '100%' }}
              >
                {d.addStep}
              </Button>
            )}

            <Table
              columns={stepColumns}
              dataSource={sequence.steps}
              rowKey="id"
              pagination={false}
              scroll={{ x: 900 }}
            />
          </Card>

          {sequence.steps.length > 0 && (
            <Card
              title={<span style={{ color: c.text }}>{d.seqFormVisualSchemaCardTitle}</span>}
              style={{ borderColor: c.borderSecondary }}
            >
              <SequenceFlow steps={sequence.steps} />
            </Card>
          )}
        </>
      )}

      <Modal
        title={editingStep ? d.seqFormEditStepModalTitle : d.addStep}
        open={isStepModalOpen}
        onCancel={() => { setIsStepModalOpen(false); setEditingStep(null); }}
        footer={null}
        width={820}
        styles={{ body: { maxHeight: '75vh', overflowY: 'auto', paddingRight: 4 } }}
      >
        <StepForm
          onSubmit={handleStepSubmit}
          onCancel={() => { setIsStepModalOpen(false); setEditingStep(null); }}
          initialValues={editingStep}
        />
      </Modal>
    </div>
  );
};
