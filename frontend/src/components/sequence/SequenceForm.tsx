import React, { useState, useEffect } from 'react';
import { Form, Input, Button, Space, notification, Card, Modal, Table, Tag } from 'antd';
import { useNavigate, useParams } from 'react-router-dom';
import { PlusOutlined, DeleteOutlined, EditOutlined } from '@ant-design/icons';
import { sequenceApi } from '../../api/sequenceApi';
import { SequenceResponse, StepResponse, StepCreateRequest } from '../../types/sequence';
import { CriteriaEditor } from './CriteriaEditor';
import { StepForm } from './StepForm';
import { SequenceFlow } from './SequenceFlow';

export const SequenceForm: React.FC = () => {
  const [form] = Form.useForm();
  const [loading, setLoading] = useState(false);
  const [sequence, setSequence] = useState<SequenceResponse | null>(null);
  const [isStepModalOpen, setIsStepModalOpen] = useState(false);
  const [editingStep, setEditingStep] = useState<StepResponse | null>(null);
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const isEditMode = id && id !== 'new';

  useEffect(() => {
    if (isEditMode) {
      loadSequence();
    }
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
        message: 'Failed to load sequence',
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
        notification.success({
          message: 'Sequence updated successfully',
        });
      } else {
        const newSequence = await sequenceApi.createSequence(request);
        notification.success({
          message: 'Sequence created successfully',
        });
        navigate(`/sequences/${newSequence.id}`);
      }
      loadSequence();
    } catch (error: any) {
      notification.error({
        message: 'Failed to save sequence',
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
        notification.success({
          message: 'Step updated successfully',
        });
      } else {
        await sequenceApi.addStep(sequence.id, stepData);
        notification.success({
          message: 'Step added successfully',
        });
      }
      setIsStepModalOpen(false);
      setEditingStep(null);
      loadSequence();
    } catch (error: any) {
      notification.error({
        message: 'Failed to save step',
        description: error.response?.data?.message || error.message,
      });
    }
  };

  const handleDeleteStep = async (stepId: number) => {
    if (!sequence) return;

    try {
      await sequenceApi.deleteStep(sequence.id, stepId);
      notification.success({
        message: 'Step deleted successfully',
      });
      loadSequence();
    } catch (error: any) {
      notification.error({
        message: 'Failed to delete step',
        description: error.response?.data?.message || error.message,
      });
    }
  };

  const stepColumns = [
    {
      title: 'Order',
      dataIndex: 'orderIndex',
      key: 'orderIndex',
      width: 80,
    },
    {
      title: 'Type',
      dataIndex: 'stepType',
      key: 'stepType',
      render: (type: string) => <Tag color="blue">{type}</Tag>,
    },
    {
      title: 'Config',
      dataIndex: 'configJson',
      key: 'configJson',
      ellipsis: true,
      render: (config: string) => {
        try {
          const parsed = JSON.parse(config);
          return parsed.actionType || parsed.criterionType || 'N/A';
        } catch {
          return 'N/A';
        }
      },
    },
    {
      title: 'Success Action',
      dataIndex: 'onSuccessAction',
      key: 'onSuccessAction',
      render: (action: string, record: StepResponse) => (
        <span>
          {action}
          {action === 'GOTO' && record.onSuccessGotoStep && ` → ${record.onSuccessGotoStep}`}
        </span>
      ),
    },
    {
      title: 'Failure Action',
      dataIndex: 'onFailureAction',
      key: 'onFailureAction',
      render: (action: string, record: StepResponse) => (
        <span>
          {action}
          {action === 'GOTO' && record.onFailureGotoStep && ` → ${record.onFailureGotoStep}`}
        </span>
      ),
    },
    {
      title: 'Actions',
      key: 'actions',
      render: (_: any, record: StepResponse) => (
        <Space>
          <Button
            size="small"
            icon={<EditOutlined />}
            onClick={() => {
              setEditingStep(record);
              setIsStepModalOpen(true);
            }}
          >
            Edit
          </Button>
          <Button
            size="small"
            danger
            icon={<DeleteOutlined />}
            onClick={() => handleDeleteStep(record.id)}
          >
            Delete
          </Button>
        </Space>
      ),
    },
  ];

  return (
    <div>
      <h2>{isEditMode ? 'Edit Sequence' : 'Create New Sequence'}</h2>

      <Card style={{ marginBottom: 16 }}>
        <Form form={form} layout="vertical" onFinish={handleSubmit}>
          <Form.Item
            name="name"
            label="Sequence Name"
            rules={[{ required: true, message: 'Please input sequence name!' }]}
          >
            <Input placeholder="Enter sequence name" />
          </Form.Item>

          <Form.Item
            name="description"
            label="Description"
            rules={[{ required: true, message: 'Please input description!' }]}
          >
            <Input.TextArea rows={3} placeholder="Enter sequence description" />
          </Form.Item>

          <Form.Item name="startCriteriaJson" label="Start Criteria (JSON)">
            <CriteriaEditor />
          </Form.Item>

          <Form.Item name="stopCriteriaJson" label="Stop Criteria (JSON)">
            <CriteriaEditor />
          </Form.Item>

          <Form.Item>
            <Space>
              <Button type="primary" htmlType="submit" loading={loading}>
                {isEditMode ? 'Update' : 'Create'} Sequence
              </Button>
              <Button onClick={() => navigate('/sequences')}>Cancel</Button>
            </Space>
          </Form.Item>
        </Form>
      </Card>

      {sequence && (
        <>
          <Card title="Steps" style={{ marginBottom: 16 }}>
            <Button
              type="dashed"
              icon={<PlusOutlined />}
              onClick={() => {
                setEditingStep(null);
                setIsStepModalOpen(true);
              }}
              style={{ marginBottom: 16, width: '100%' }}
            >
              Add Step
            </Button>

            <Table
              columns={stepColumns}
              dataSource={sequence.steps}
              rowKey="id"
              pagination={false}
            />
          </Card>

          {sequence.steps.length > 0 && (
            <Card title="Visual Flow">
              <SequenceFlow steps={sequence.steps} />
            </Card>
          )}
        </>
      )}

      <Modal
        title={editingStep ? 'Edit Step' : 'Add New Step'}
        open={isStepModalOpen}
        onCancel={() => {
          setIsStepModalOpen(false);
          setEditingStep(null);
        }}
        footer={null}
        width={800}
      >
        <StepForm
          onSubmit={handleStepSubmit}
          onCancel={() => {
            setIsStepModalOpen(false);
            setEditingStep(null);
          }}
          initialValues={editingStep}
        />
      </Modal>
    </div>
  );
};
