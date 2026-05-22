import React, { useState } from 'react';
import { Button, Drawer, Tag, Collapse } from 'antd';
import { QuestionCircleOutlined } from '@ant-design/icons';

const GUIDE_ITEMS = [
  {
    key: '1',
    label: '📋 Как отправить сообщение',
    children: (
      <div style={{ lineHeight: 1.9, fontSize: 13 }}>
        <p><strong>Шаг 1 — Тип сообщения:</strong></p>
        <div style={{ marginLeft: 12, marginBottom: 8 }}>
          <div style={{ marginBottom: 4 }}><Tag color="blue">DOWNLINK</Tag> — борт → земля (позиция, доклады экипажа)</div>
          <div style={{ marginBottom: 4 }}><Tag color="orange">GROUND</Tag> — наземное (метео, инструкции, задержки)</div>
          <div><Tag color="purple">UPLINK</Tag> — земля → борт (команды, запросы)</div>
        </div>
        <p><strong>Шаг 2 — Шаблон сообщения</strong> (строка, регистр важен):</p>
        <div style={{ marginLeft: 12, marginBottom: 8, fontFamily: 'monospace', fontSize: 12 }}>
          <div style={{ marginBottom: 3 }}><code>POSITION_REPORT</code> — позиционный отчёт</div>
          <div style={{ marginBottom: 3 }}><code>WEATHER_UPDATE</code> — метеосводка</div>
          <div style={{ marginBottom: 3 }}><code>DELAY_NOTICE</code> — задержка рейса</div>
          <div style={{ marginBottom: 3 }}><code>PREFLIGHT_COMPLETE</code> — готовность к вылету</div>
          <div><code>LANDING_REPORT</code> — доклад о посадке</div>
        </div>
        <p><strong>Шаг 3 — Борт:</strong> <code>VP-BQR</code>, <code>RA-89050</code>, <code>SU9876</code>, <code>SU1234</code></p>
        <p><strong>Шаг 4</strong> — нажать <em>Отправить</em>. Если есть активная последовательность с критерием для этого шаблона — она запустится автоматически.</p>
      </div>
    ),
  },
  {
    key: '2',
    label: '📨 Примеры готовых сообщений',
    children: (
      <div>
        {[
          {
            title: 'Позиционный отчёт',
            type: 'DOWNLINK', tmpl: 'POSITION_REPORT', ac: 'VP-BQR', fl: 'SU1234',
            meta: '{"latitude": 55.7558, "longitude": 37.6173}',
            hint: 'Запустит "Запрос позиционного отчёта после взлёта"',
          },
          {
            title: 'Метеосводка',
            type: 'GROUND', tmpl: 'WEATHER_UPDATE', ac: 'SU9876', fl: 'AFL123',
            meta: '{"temperature":-5,"wind":"270/10kt"}',
            hint: 'Запустит "Распределение метеоинформации"',
          },
          {
            title: 'Задержка рейса',
            type: 'GROUND', tmpl: 'DELAY_NOTICE', ac: 'SU9876', fl: 'AFL123',
            meta: '{"reason":"weather","delayMinutes":30}',
            hint: 'Запустит "Уведомление о задержке рейса"',
          },
          {
            title: 'Предполётная готовность',
            type: 'DOWNLINK', tmpl: 'PREFLIGHT_COMPLETE', ac: 'SU1234', fl: 'AFL456',
            meta: '{"pilot":"Ivanov","copilot":"Petrov"}',
            hint: 'Отвечает на WAIT-шаг в "Предполётная подготовка"',
          },
        ].map(ex => (
          <div key={ex.title} style={{
            background: 'rgba(0,0,0,0.03)',
            border: '1px solid rgba(0,0,0,0.08)',
            borderRadius: 10, padding: '12px 14px', marginBottom: 12,
          }}>
            <div style={{ fontWeight: 600, fontSize: 13, marginBottom: 6 }}>{ex.title}</div>
            <div style={{ fontSize: 12, fontFamily: 'monospace', lineHeight: 1.7, color: 'rgba(0,0,0,0.65)' }}>
              <div>Тип: <Tag style={{ fontSize: 10 }}>{ex.type}</Tag></div>
              <div>Шаблон: <code>{ex.tmpl}</code></div>
              <div>ВС: <code>{ex.ac}</code> · Рейс: <code>{ex.fl}</code></div>
              <div style={{ wordBreak: 'break-all' }}>Метаданные: <code>{ex.meta}</code></div>
            </div>
            <div style={{ fontSize: 11, color: '#10b981', marginTop: 6 }}>💡 {ex.hint}</div>
          </div>
        ))}
      </div>
    ),
  },
  {
    key: '3',
    label: '⚡ Смена фазы полёта',
    children: (
      <div style={{ fontSize: 13, lineHeight: 1.9 }}>
        <p>Переключись на вкладку <strong>"Изменить фазу полёта"</strong> для симуляции перехода между фазами:</p>
        <div style={{ marginLeft: 12 }}>
          <div><Tag>INIT</Tag> — начало предполётной подготовки</div>
          <div><Tag>OUT</Tag> — выруливание на ВПП</div>
          <div><Tag>OFF</Tag> — взлёт → запускает "Запрос позиционного отчёта"</div>
          <div><Tag>ON</Tag>  — посадка → запускает "Контроль связи после посадки"</div>
          <div><Tag>IN</Tag>  — заруливание на стоянку</div>
        </div>
        <p style={{ marginTop: 10, color: 'rgba(0,0,0,0.55)' }}>
          Используй борт <code>SU9876</code> или <code>RA-89050</code> — для них в базе есть активные последовательности.
        </p>
      </div>
    ),
  },
];

export const SimulatorGuide: React.FC = () => {
  const [open, setOpen] = useState(false);
  return (
    <>
      <Button
        icon={<QuestionCircleOutlined />}
        onClick={() => setOpen(true)}
        style={{ borderRadius: 20 }}
      >
        Что писать?
      </Button>
      <Drawer
        title="📚 Методичка симулятора"
        placement="right"
        width={500}
        open={open}
        onClose={() => setOpen(false)}
      >
        <Collapse defaultActiveKey={['1']} items={GUIDE_ITEMS} />
      </Drawer>
    </>
  );
};
