import React, { useState } from 'react';
import {
  Card, Form, Input, Select, Button, Space, Radio, Tag,
  Typography, Row, Col, AutoComplete, Alert, Spin,
} from 'antd';
import { useNotification } from '../../hooks/useNotification';
import {
  SendOutlined, ThunderboltOutlined, CheckCircleOutlined,
} from '@ant-design/icons';
import { SimulatorGuide } from './SimulatorGuide';
import { useNavigate } from 'react-router-dom';
import { messageApi } from '../../api/messageApi';
import { executionApi } from '../../api/executionApi';
import { ExecutionInstanceResponse } from '../../types/execution';
import { useTheme } from '../../context/ThemeContext';

const { Text } = Typography;

const RECENT_AIRCRAFT_KEY = 'eca:recent_aircraft';
const SIM_HISTORY_KEY = 'eca:sim_history';
const MAX_RECENT = 8;
const MAX_HISTORY = 5;

const getRecentAircraft = (): string[] => {
  try { return JSON.parse(localStorage.getItem(RECENT_AIRCRAFT_KEY) ?? '[]'); } catch { return []; }
};

const saveRecentAircraft = (id: string) => {
  const list = [id, ...getRecentAircraft().filter(x => x !== id)].slice(0, MAX_RECENT);
  localStorage.setItem(RECENT_AIRCRAFT_KEY, JSON.stringify(list));
};

interface SimHistoryEntry {
  type: 'message' | 'stage';
  label: string;
  aircraft: string;
  ts: string;
}

const getSimHistory = (): SimHistoryEntry[] => {
  try { return JSON.parse(localStorage.getItem(SIM_HISTORY_KEY) ?? '[]'); } catch { return []; }
};

const addSimHistory = (entry: SimHistoryEntry) => {
  const list = [entry, ...getSimHistory()].slice(0, MAX_HISTORY);
  localStorage.setItem(SIM_HISTORY_KEY, JSON.stringify(list));
};

const STATUS_LABEL: Record<string, string> = {
  RUNNING: 'Выполняется', WAITING: 'Ожидание',
  COMPLETED: 'Завершено', ABORTED: 'Прервано',
};

const STATUS_COLOR: Record<string, string> = {
  RUNNING: 'processing', WAITING: 'warning',
  COMPLETED: 'success', ABORTED: 'error',
};

export const MessageSimulator: React.FC = () => {
  const notification = useNotification();
  const [form] = Form.useForm();
  const [loading, setLoading] = useState(false);
  const [simulationType, setSimulationType] = useState<'message' | 'stage'>('message');
  const [recentAircraft, setRecentAircraft] = useState<string[]>(getRecentAircraft);
  const [triggeredExecs, setTriggeredExecs] = useState<ExecutionInstanceResponse[] | null>(null);
  const [checkingExecs, setCheckingExecs] = useState(false);
  const [history, setHistory] = useState<SimHistoryEntry[]>(getSimHistory);
  const { isDark } = useTheme();
  const navigate = useNavigate();

  const c = isDark
    ? { borderSecondary: '#21262d', text: '#e6edf3', textMuted: '#848d97', textDimmer: '#484f58', bgElevated: '#1c2128' }
    : { borderSecondary: '#d8dee4', text: '#1f2328', textMuted: '#636c76', textDimmer: '#9da3ab', bgElevated: '#f6f8fa' };

  const fetchRecentExecutions = async (aircraftId: string) => {
    setCheckingExecs(true);
    try {
      // Small delay to let the backend process the event and create execution
      await new Promise(r => setTimeout(r, 1200));
      const data = await executionApi.getExecutions(0, 3, undefined, aircraftId);
      setTriggeredExecs(data.content);
    } catch {
      setTriggeredExecs([]);
    } finally {
      setCheckingExecs(false);
    }
  };

  const handleSendMessage = async (values: any) => {
    setLoading(true);
    setTriggeredExecs(null);
    try {
      await messageApi.sendMessage({
        messageType: values.messageType,
        templateName: values.templateName,
        aircraftId: values.aircraftId,
        flightNumber: values.flightNumber || undefined,
        metadataJson: values.metadataJson || undefined,
      });
      notification.success({ message: 'Сообщение отправлено успешно' });
      saveRecentAircraft(values.aircraftId);
      setRecentAircraft(getRecentAircraft());
      addSimHistory({ type: 'message', label: `${values.messageType} / ${values.templateName}`, aircraft: values.aircraftId, ts: new Date().toISOString() });
      setHistory(getSimHistory());
      form.resetFields(['templateName', 'metadataJson']);
      fetchRecentExecutions(values.aircraftId);
    } catch (error: any) {
      notification.error({
        message: 'Ошибка отправки сообщения',
        description: error.response?.data?.message || error.message,
      });
    } finally {
      setLoading(false);
    }
  };

  const handleStageChange = async (values: any) => {
    setLoading(true);
    setTriggeredExecs(null);
    try {
      await messageApi.changeFlightStage({
        aircraftId: values.aircraftId,
        flightNumber: values.flightNumber || undefined,
        newStage: values.newStage,
      });
      notification.success({ message: 'Фаза полёта изменена успешно' });
      saveRecentAircraft(values.aircraftId);
      setRecentAircraft(getRecentAircraft());
      addSimHistory({ type: 'stage', label: `FlightStage → ${values.newStage}`, aircraft: values.aircraftId, ts: new Date().toISOString() });
      setHistory(getSimHistory());
      form.resetFields(['newStage']);
      fetchRecentExecutions(values.aircraftId);
    } catch (error: any) {
      notification.error({
        message: 'Ошибка смены фазы полёта',
        description: error.response?.data?.message || error.message,
      });
    } finally {
      setLoading(false);
    }
  };

  const aircraftOptions = recentAircraft.map(id => ({ value: id, label: id }));

  // Быстрые пресеты для заполнения формы
  const QUICK_PRESETS = [
    { label: '📡 Позиция',   type: 'DOWNLINK', tmpl: 'POSITION_REPORT',   ac: 'VP-BQR',   fl: 'SU1234', meta: '{"latitude":55.7558,"longitude":37.6173,"positionSource":"ACARS","estimatedPosition":false}' },
    { label: '🌤 Метео',     type: 'GROUND',   tmpl: 'WEATHER_UPDATE',     ac: 'SU9876',   fl: 'AFL123', meta: '{"temperature":-5,"wind":"270/10kt"}' },
    { label: '⏰ Задержка',  type: 'GROUND',   tmpl: 'DELAY_NOTICE',       ac: 'SU9876',   fl: 'AFL123', meta: '{"reason":"weather","delayMinutes":30}' },
    { label: '✈ Предполёт', type: 'DOWNLINK', tmpl: 'PREFLIGHT_COMPLETE', ac: 'SU1234',   fl: 'AFL456', meta: '{"pilot":"Ivanov","copilot":"Petrov"}' },
    { label: '🛬 Посадка',  type: 'DOWNLINK', tmpl: 'LANDING_REPORT',     ac: 'RA-89050', fl: 'SU777',  meta: '{"runway":"25L"}' },
  ];

  const applyPreset = (p: typeof QUICK_PRESETS[0]) => {
    setSimulationType('message');
    setTimeout(() => form.setFieldsValue({
      messageType: p.type, templateName: p.tmpl,
      aircraftId: p.ac, flightNumber: p.fl, metadataJson: p.meta,
    }), 0);
  };

  return (
    <div className="fade-in-up">
      <div className="page-header">
        <h2 className="page-title">Симулятор событий</h2>
      </div>

      <Card style={{ borderColor: c.borderSecondary }}>
        {/* Быстрые пресеты */}
        <div style={{ marginBottom: 16 }}>
          <div style={{ fontSize: 12, color: c.textMuted, marginBottom: 8 }}>
            Быстрые сценарии (клик заполняет форму):
          </div>
          <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap', alignItems: 'center' }}>
            {QUICK_PRESETS.map(p => (
              <button
                key={p.label}
                onClick={() => applyPreset(p)}
                style={{
                  padding: '5px 14px', borderRadius: 20,
                  border: `1px solid ${c.borderSecondary}`,
                  background: 'transparent', color: c.text,
                  fontSize: 13, cursor: 'pointer',
                  transition: 'all 0.15s ease',
                }}
                onMouseEnter={e => {
                  (e.currentTarget as HTMLElement).style.background = 'rgba(59,130,246,0.08)';
                  (e.currentTarget as HTMLElement).style.borderColor = 'rgba(59,130,246,0.35)';
                  (e.currentTarget as HTMLElement).style.color = '#3b82f6';
                }}
                onMouseLeave={e => {
                  (e.currentTarget as HTMLElement).style.background = 'transparent';
                  (e.currentTarget as HTMLElement).style.borderColor = c.borderSecondary;
                  (e.currentTarget as HTMLElement).style.color = c.text;
                }}
              >
                {p.label}
              </button>
            ))}
            <SimulatorGuide />
          </div>
        </div>

        <Radio.Group
          value={simulationType}
          onChange={(e) => {
            setSimulationType(e.target.value);
            setTriggeredExecs(null);
            form.resetFields();
          }}
          style={{ marginBottom: 20 }}
          buttonStyle="solid"
        >
          <Radio.Button value="message">
            <SendOutlined style={{ marginRight: 6 }} />
            Отправить сообщение
          </Radio.Button>
          <Radio.Button value="stage">
            <ThunderboltOutlined style={{ marginRight: 6 }} />
            Изменить фазу полёта
          </Radio.Button>
        </Radio.Group>

        {simulationType === 'message' ? (
          <Form
            form={form}
            layout="vertical"
            onFinish={handleSendMessage}
            initialValues={{ messageType: 'DOWNLINK' }}
          >
            <Row gutter={16}>
              <Col span={12}>
                <Form.Item
                  name="messageType"
                  label="Тип сообщения"
                  rules={[{ required: true, message: 'Выберите тип' }]}
                >
                  <Select>
                    <Select.Option value="DOWNLINK">
                      <Tag color="blue" style={{ marginRight: 4 }}>DOWNLINK</Tag>
                      Борт → земля
                    </Select.Option>
                    <Select.Option value="UPLINK">
                      <Tag color="green" style={{ marginRight: 4 }}>UPLINK</Tag>
                      Земля → борт
                    </Select.Option>
                    <Select.Option value="GROUND">
                      <Tag color="orange" style={{ marginRight: 4 }}>GROUND</Tag>
                      Наземная
                    </Select.Option>
                  </Select>
                </Form.Item>
              </Col>
              <Col span={12}>
                <Form.Item
                  name="templateName"
                  label="Шаблон сообщения"
                  rules={[{ required: true, message: 'Введите шаблон' }]}
                >
                  <Input placeholder="POSITION_REPORT, WEATHER_UPDATE…" />
                </Form.Item>
              </Col>
            </Row>

            <Row gutter={16}>
              <Col span={12}>
                <Form.Item
                  name="aircraftId"
                  label="Идентификатор ВС"
                  rules={[{ required: true, message: 'Введите идентификатор ВС' }]}
                >
                  <AutoComplete
                    options={aircraftOptions}
                    placeholder="VP-BQR"
                    filterOption={(input, opt) =>
                      (opt?.value ?? '').toLowerCase().includes(input.toLowerCase())
                    }
                  />
                </Form.Item>
              </Col>
              <Col span={12}>
                <Form.Item name="flightNumber" label="Номер рейса (необязательно)">
                  <Input placeholder="SU1234" />
                </Form.Item>
              </Col>
            </Row>

            <Form.Item
              name="metadataJson"
              label="Метаданные (JSON, необязательно)"
              rules={[
                {
                  validator: async (_, value) => {
                    if (!value?.trim()) return;
                    try {
                      JSON.parse(value);
                    } catch {
                      throw new Error('Введите валидный JSON. Пример: {"key": "value"}');
                    }
                  },
                },
              ]}
            >
              <Input.TextArea
                rows={3}
                placeholder='{"latitude": 55.7558, "longitude": 37.6173}'
                style={{ fontFamily: 'monospace', fontSize: 13 }}
              />
            </Form.Item>

            <Form.Item style={{ marginBottom: 0 }}>
              <Button type="primary" htmlType="submit" icon={<SendOutlined />} loading={loading}>
                Отправить сообщение
              </Button>
            </Form.Item>
          </Form>
        ) : (
          <Form form={form} layout="vertical" onFinish={handleStageChange}>
            <Row gutter={16}>
              <Col span={12}>
                <Form.Item
                  name="aircraftId"
                  label="Идентификатор ВС"
                  rules={[{ required: true, message: 'Введите идентификатор ВС' }]}
                >
                  <AutoComplete
                    options={aircraftOptions}
                    placeholder="VP-BQR"
                    filterOption={(input, opt) =>
                      (opt?.value ?? '').toLowerCase().includes(input.toLowerCase())
                    }
                  />
                </Form.Item>
              </Col>
              <Col span={12}>
                <Form.Item name="flightNumber" label="Номер рейса (необязательно)">
                  <Input placeholder="SU1234" />
                </Form.Item>
              </Col>
            </Row>

            <Form.Item
              name="newStage"
              label="Новая фаза полёта"
              rules={[{ required: true, message: 'Выберите фазу полёта' }]}
            >
              <Select>
                <Select.Option value="INIT">INIT — Начальная</Select.Option>
                <Select.Option value="OUT">OUT — Выруливание на ВПП</Select.Option>
                <Select.Option value="OFF">OFF — Взлёт</Select.Option>
                <Select.Option value="ON">ON — Посадка</Select.Option>
                <Select.Option value="IN">IN — Заруливание</Select.Option>
              </Select>
            </Form.Item>

            <Form.Item style={{ marginBottom: 0 }}>
              <Button type="primary" htmlType="submit" icon={<ThunderboltOutlined />} loading={loading}>
                Изменить фазу
              </Button>
            </Form.Item>
          </Form>
        )}
      </Card>

      {/* Result after send */}
      {(checkingExecs || triggeredExecs !== null) && (
        <Card
          title={
            <Space>
              <CheckCircleOutlined style={{ color: '#52c41a' }} />
              <span style={{ color: c.text }}>Результат обработки события</span>
            </Space>
          }
          style={{ marginTop: 16, borderColor: '#52c41a' }}
        >
          {checkingExecs ? (
            <div style={{ textAlign: 'center', padding: '16px 0' }}>
              <Spin />
              <div style={{ color: c.textMuted, marginTop: 8, fontSize: 13 }}>
                Проверяем запущенные последовательности…
              </div>
            </div>
          ) : triggeredExecs && triggeredExecs.length > 0 ? (
            <Space direction="vertical" style={{ width: '100%' }}>
              <Text style={{ color: c.textMuted, fontSize: 13 }}>
                Последние выполнения для этого ВС:
              </Text>
              {triggeredExecs.map(exec => (
                <div
                  key={exec.id}
                  onClick={() => navigate(`/executions/${exec.id}`)}
                  style={{
                    padding: '10px 14px',
                    borderRadius: 8,
                    border: `1px solid ${c.borderSecondary}`,
                    background: c.bgElevated,
                    cursor: 'pointer',
                    display: 'flex',
                    alignItems: 'center',
                    gap: 10,
                    transition: 'border-color 0.15s',
                  }}
                  onMouseEnter={e => (e.currentTarget.style.borderColor = '#1677ff')}
                  onMouseLeave={e => (e.currentTarget.style.borderColor = c.borderSecondary)}
                >
                  <Tag color={STATUS_COLOR[exec.status]} style={{ margin: 0, minWidth: 90, textAlign: 'center' }}>
                    {STATUS_LABEL[exec.status] ?? exec.status}
                  </Tag>
                  <Text style={{ color: c.text, fontWeight: 500, flex: 1 }}>{exec.sequenceName}</Text>
                  <Text style={{ color: c.textMuted, fontSize: 12 }}>
                    Шаг {exec.currentStepIndex ?? '—'} · {new Date(exec.startedAt).toLocaleTimeString('ru-RU')}
                  </Text>
                </div>
              ))}
            </Space>
          ) : (
            <Alert
              message="Ни одна последовательность не запустилась"
              description="Возможно, нет активной последовательности, соответствующей этому событию, или событие обрабатывается асинхронно."
              type="info"
              showIcon
            />
          )}
        </Card>
      )}

      {/* Send history */}
      {history.length > 0 && (
        <Card
          title={<span style={{ color: c.text }}>Последние отправленные</span>}
          extra={
            <Button type="text" size="small" style={{ color: c.textDimmer, fontSize: 11 }}
              onClick={() => { localStorage.removeItem(SIM_HISTORY_KEY); setHistory([]); }}>
              Очистить
            </Button>
          }
          style={{ marginTop: 16, borderColor: c.borderSecondary }}
        >
          <Space wrap>
            {history.map((h, i) => (
              <Tag key={i} color={h.type === 'message' ? 'processing' : 'warning'}
                style={{ cursor: 'default', borderRadius: 20, padding: '3px 10px' }}>
                ✈ {h.aircraft} · {h.label}
                <span style={{ color: c.textDimmer, marginLeft: 6, fontSize: 10 }}>
                  {new Date(h.ts).toLocaleTimeString('ru-RU')}
                </span>
              </Tag>
            ))}
          </Space>
        </Card>
      )}

      {/* Ready scenarios */}
      <Card
        title={<span style={{ color: c.text }}>Готовые сценарии</span>}
        style={{ marginTop: 16, borderColor: c.borderSecondary }}
      >
        <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
          {[
            {
              icon: '📡', color: '#3b82f6',
              title: 'Позиционный отчёт от борта',
              badge: 'DOWNLINK · POSITION_REPORT',
              ac: 'VP-BQR', fl: 'SU1234',
              note: 'Запустит "Запрос позиционного отчёта после взлёта"',
              fill: () => { setSimulationType('message'); setTimeout(() => form.setFieldsValue({ messageType: 'DOWNLINK', templateName: 'POSITION_REPORT', aircraftId: 'VP-BQR', flightNumber: 'SU1234', metadataJson: '{"latitude":55.7558,"longitude":37.6173,"positionSource":"ACARS","estimatedPosition":false}' }), 0); },
            },
            {
              icon: '⚡', color: '#10b981',
              title: 'Смена фазы: взлёт (FlightStage = OFF)',
              badge: 'FlightStage · OFF',
              ac: 'VP-BQR', fl: 'SU1234',
              note: 'Запустит "Запрос позиционного отчёта после взлёта"',
              fill: () => { setSimulationType('stage'); setTimeout(() => form.setFieldsValue({ aircraftId: 'VP-BQR', flightNumber: 'SU1234', newStage: 'OFF' }), 0); },
            },
            {
              icon: '🌤', color: '#f59e0b',
              title: 'Метеосводка для рейса',
              badge: 'GROUND · WEATHER_UPDATE',
              ac: 'SU9876', fl: 'AFL123',
              note: 'Запустит "Распределение метеоинформации"',
              fill: () => { setSimulationType('message'); setTimeout(() => form.setFieldsValue({ messageType: 'GROUND', templateName: 'WEATHER_UPDATE', aircraftId: 'SU9876', flightNumber: 'AFL123', metadataJson: '{"temperature":-5,"wind":"10kt"}' }), 0); },
            },
            {
              icon: '⏰', color: '#ef4444',
              title: 'Уведомление о задержке рейса',
              badge: 'GROUND · DELAY_NOTICE',
              ac: 'SU9876', fl: 'AFL123',
              note: 'Запустит "Уведомление о задержке рейса"',
              fill: () => { setSimulationType('message'); setTimeout(() => form.setFieldsValue({ messageType: 'GROUND', templateName: 'DELAY_NOTICE', aircraftId: 'SU9876', flightNumber: 'AFL123', metadataJson: '{"reason":"weather","delayMinutes":30}' }), 0); },
            },
            {
              icon: '🛫', color: '#8b5cf6',
              title: 'Начало предполётной подготовки (FlightStage = INIT)',
              badge: 'FlightStage · INIT',
              ac: 'SU1234', fl: 'AFL456',
              note: 'Запустит "Предполётная подготовка"',
              fill: () => { setSimulationType('stage'); setTimeout(() => form.setFieldsValue({ aircraftId: 'SU1234', flightNumber: 'AFL456', newStage: 'INIT' }), 0); },
            },
          ].map((s, i) => (
            <div
              key={i}
              onClick={s.fill}
              style={{
                padding: '12px 16px', borderRadius: 10,
                background: c.bgElevated,
                border: `1px solid ${c.borderSecondary}`,
                borderLeft: `4px solid ${s.color}`,
                cursor: 'pointer',
                transition: 'all 0.15s ease',
              }}
              onMouseEnter={e => {
                (e.currentTarget as HTMLElement).style.borderColor = s.color;
                (e.currentTarget as HTMLElement).style.background = `${s.color}12`;
              }}
              onMouseLeave={e => {
                (e.currentTarget as HTMLElement).style.borderColor = c.borderSecondary;
                (e.currentTarget as HTMLElement).style.background = c.bgElevated;
              }}
            >
              <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start' }}>
                <div style={{ fontWeight: 600, fontSize: 14, color: s.color }}>
                  {s.icon} {s.title}
                </div>
                <Tag style={{ fontSize: 10, flexShrink: 0, marginLeft: 8 }}>нажмите для заполнения</Tag>
              </div>
              <div style={{ fontSize: 12, color: c.textMuted, marginTop: 3 }}>
                <code style={{ background: 'transparent' }}>{s.badge}</code>
                {' '}· ВС: <code style={{ background: 'transparent' }}>{s.ac}</code>
                {' '}· Рейс: <code style={{ background: 'transparent' }}>{s.fl}</code>
              </div>
              <div style={{ fontSize: 11, color: '#10b981', marginTop: 4 }}>💡 {s.note}</div>
            </div>
          ))}
        </div>
      </Card>
    </div>
  );
};
