import React, { useState, useEffect } from 'react';
import { Form, Input, Button, Space, notification, Card, Modal, Table, Tag, Tooltip } from 'antd';
import { useNavigate, useParams } from 'react-router-dom';
import { PlusOutlined, DeleteOutlined, EditOutlined, ArrowUpOutlined, ArrowDownOutlined } from '@ant-design/icons';
import { sequenceApi } from '../../api/sequenceApi';
import { SequenceResponse, StepResponse, StepCreateRequest } from '../../types/sequence';
import { CriteriaEditor } from './CriteriaEditor';
import { StepForm } from './StepForm';
import { SequenceFlow } from './SequenceFlow';
import { useTheme } from '../../context/ThemeContext';
import { useAuth } from '../../hooks/useAuth';

const STEP_TYPE_COLOR: Record<string, string> = {
  ACTION:   'blue',
  EVALUATE: 'gold',
  WAIT:     'purple',
};

const STEP_TYPE_LABEL: Record<string, string> = {
  ACTION:   'Действие',
  EVALUATE: 'Оценка',
  WAIT:     'Ожидание',
};

export const SequenceForm: React.FC = () => {
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
    ? { borderSecondary: '#21262d', text: '#e6edf3' }
    : { borderSecondary: '#d8dee4', text: '#1f2328' };

  useEffect(() => {
    if (isEditMode) loadSequence();
  }, [id]);

  const loadSequence = async () => {
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
        message: 'Ошибка загрузки последовательности',
        description: error.response?.data?.message || error.message,
      });
      navigate('/sequences');
    } finally {
      setLoading(false);
    }
  };

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
        notification.success({ message: 'Последовательность обновлена' });
      } else {
        const newSeq = await sequenceApi.createSequence(request);
        notification.success({ message: 'Последовательность создана' });
        navigate(`/sequences/${newSeq.id}`);
      }
      loadSequence();
    } catch (error: any) {
      notification.error({
        message: 'Ошибка сохранения',
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
        notification.success({ message: 'Шаг обновлён' });
      } else {
        await sequenceApi.addStep(sequence.id, stepData);
        notification.success({ message: 'Шаг добавлен' });
      }
      setIsStepModalOpen(false);
      setEditingStep(null);
      loadSequence();
    } catch (error: any) {
      notification.error({
        message: 'Ошибка сохранения шага',
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
      loadSequence();
    } catch (error: any) {
      notification.error({
        message: 'Ошибка изменения порядка шагов',
        description: error.response?.data?.message || error.message,
      });
    }
  };

  const handleDeleteStep = async (stepId: number) => {
    if (!sequence) return;
    try {
      await sequenceApi.deleteStep(sequence.id, stepId);
      notification.success({ message: 'Шаг удалён' });
      loadSequence();
    } catch (error: any) {
      notification.error({
        message: 'Ошибка удаления шага',
        description: error.response?.data?.message || error.message,
      });
    }
  };

  const stepColumns = [
    { title: '№', dataIndex: 'orderIndex', key: 'orderIndex', width: 60 },
    {
      title: 'Тип',
      dataIndex: 'stepType',
      key: 'stepType',
      render: (type: string) => (
        <Tag color={STEP_TYPE_COLOR[type] ?? 'blue'}>{STEP_TYPE_LABEL[type] ?? type}</Tag>
      ),
    },
    {
      title: 'Конфигурация',
      dataIndex: 'configJson',
      key: 'configJson',
      ellipsis: true,
      render: (config: string) => {
        try {
          const parsed = JSON.parse(config);
          const label = parsed.actionType || parsed.criterionType || '—';
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
      title: 'При успехе',
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
      title: 'При ошибке',
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
      title: 'Действия',
      key: 'actions',
      render: (_: any, record: StepResponse) => {
        const steps = sequence?.steps ?? [];
        const sortedSteps = [...steps].sort((a, b) => a.orderIndex - b.orderIndex);
        const idx = sortedSteps.findIndex(s => s.id === record.id);
        return (
          <Space size={4}>
            {isAdmin && (
              <Button
                size="small"
                icon={<ArrowUpOutlined />}
                disabled={idx === 0}
                onClick={() => handleMoveStep(record.id, 'up')}
                title="Переместить вверх"
              />
            )}
            {isAdmin && (
              <Button
                size="small"
                icon={<ArrowDownOutlined />}
                disabled={idx === sortedSteps.length - 1}
                onClick={() => handleMoveStep(record.id, 'down')}
                title="Переместить вниз"
              />
            )}
            {isAdmin && (
              <Button
                size="small"
                icon={<EditOutlined />}
                onClick={() => { setEditingStep(record); setIsStepModalOpen(true); }}
              >
                Изменить
              </Button>
            )}
            {isAdmin && (
              <Button
                size="small"
                danger
                icon={<DeleteOutlined />}
                onClick={() => handleDeleteStep(record.id)}
              >
                Удалить
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
        {isEditMode ? 'Редактировать последовательность' : 'Создать последовательность'}
      </h2>

      <Card style={{ marginBottom: 16, borderColor: c.borderSecondary }}>
        <Form form={form} layout="vertical" onFinish={handleSubmit}>
          <Form.Item
            name="name"
            label="Название последовательности"
            rules={[{ required: true, message: 'Введите название' }]}
          >
            <Input placeholder="Например: Мониторинг полёта AFL123" />
          </Form.Item>

          <Form.Item
            name="description"
            label="Описание"
            rules={[{ required: true, message: 'Введите описание' }]}
          >
            <Input.TextArea rows={3} placeholder="Краткое описание назначения последовательности" />
          </Form.Item>

          <Form.Item name="startCriteriaJson" label="Критерий запуска (JSON)">
            <CriteriaEditor />
          </Form.Item>

          <Form.Item name="stopCriteriaJson" label="Критерий остановки (JSON)">
            <CriteriaEditor />
          </Form.Item>

          <Form.Item>
            <Space>
              {isAdmin && (
                <Button type="primary" htmlType="submit" loading={loading}>
                  {isEditMode ? 'Сохранить' : 'Создать'} последовательность
                </Button>
              )}
              <Button onClick={() => navigate('/sequences')}>
                {isAdmin ? 'Отмена' : 'Назад'}
              </Button>
            </Space>
          </Form.Item>
        </Form>
      </Card>

      {sequence && (
        <>
          <Card
            title={<span style={{ color: c.text }}>Шаги последовательности</span>}
            style={{ marginBottom: 16, borderColor: c.borderSecondary }}
          >
            {isAdmin && (
              <Button
                type="dashed"
                icon={<PlusOutlined />}
                onClick={() => { setEditingStep(null); setIsStepModalOpen(true); }}
                style={{ marginBottom: 16, width: '100%' }}
              >
                Добавить шаг
              </Button>
            )}

            <Table
              columns={stepColumns}
              dataSource={sequence.steps}
              rowKey="id"
              pagination={false}
            />
          </Card>

          {sequence.steps.length > 0 && (
            <Card
              title={<span style={{ color: c.text }}>Визуальная схема</span>}
              style={{ borderColor: c.borderSecondary }}
            >
              <SequenceFlow steps={sequence.steps} />
            </Card>
          )}
        </>
      )}

      <Modal
        title={editingStep ? 'Редактировать шаг' : 'Добавить шаг'}
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
