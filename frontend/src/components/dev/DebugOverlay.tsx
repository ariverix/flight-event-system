import React, { useState, useEffect, useCallback } from 'react';

// Только в dev режиме — в production не рендерится

interface LogEntry {
  id:      number;
  time:    string;
  type:    'error' | 'warn' | 'info' | 'success';
  message: string;
  detail?: string;
}

let logId = 0;

export const DebugOverlay: React.FC = () => {
  const [logs,     setLogs]     = useState<LogEntry[]>([]);
  const [visible,  setVisible]  = useState(false);
  const [minimized, setMin]     = useState(false);

  const addLog = useCallback((type: LogEntry['type'], msg: string, detail?: string) => {
    setLogs(prev => [{
      id:      ++logId,
      time:    new Date().toLocaleTimeString('ru-RU', { hour:'2-digit', minute:'2-digit', second:'2-digit' }),
      type,
      message: msg.slice(0, 300),
      detail,
    }, ...prev].slice(0, 50));
    if (type === 'error') setVisible(true);
  }, []);

  // Перехват console.error / console.warn + window errors
  useEffect(() => {
    if (import.meta.env.PROD) return;

    const origError = console.error.bind(console);
    const origWarn  = console.warn.bind(console);

    console.error = (...args: unknown[]) => {
      origError(...args);
      addLog('error', args.map(String).join(' '));
    };
    console.warn = (...args: unknown[]) => {
      origWarn(...args);
      addLog('warn', args.map(String).join(' '));
    };

    const onError   = (e: ErrorEvent)           => addLog('error', e.message, `${e.filename}:${e.lineno}`);
    const onPromise = (e: PromiseRejectionEvent) => addLog('error', `Promise: ${String(e.reason)}`);

    window.addEventListener('error', onError);
    window.addEventListener('unhandledrejection', onPromise);

    // Глобальные хелперы для отладки из консоли
    (window as any).__ecaLog   = (m: string) => addLog('info', m);
    (window as any).__ecaError = (m: string) => addLog('error', m);

    return () => {
      console.error = origError;
      console.warn  = origWarn;
      window.removeEventListener('error', onError);
      window.removeEventListener('unhandledrejection', onPromise);
    };
  }, [addLog]);

  // Alt+D — горячая клавиша
  useEffect(() => {
    const handler = (e: KeyboardEvent) => { if (e.altKey && e.key === 'd') setVisible(v => !v); };
    window.addEventListener('keydown', handler);
    return () => window.removeEventListener('keydown', handler);
  }, []);

  if (import.meta.env.PROD) return null;

  const COLORS = { error: '#ef4444', warn: '#f59e0b', info: '#3b82f6', success: '#10b981' };
  const ICONS  = { error: '❌', warn: '⚠️', info: 'ℹ️', success: '✅' };
  const errorCount = logs.filter(l => l.type === 'error').length;

  return (
    <>
      {/* Кнопка-триггер */}
      <div
        onClick={() => setVisible(v => !v)}
        style={{
          position: 'fixed', bottom: 16, right: 16, zIndex: 9999,
          background: errorCount > 0 ? '#ef4444' : '#1d1d1f',
          color: '#fff', borderRadius: 20, padding: '6px 14px',
          fontSize: 12, fontWeight: 600, cursor: 'pointer',
          boxShadow: '0 4px 12px rgba(0,0,0,0.35)',
          display: 'flex', alignItems: 'center', gap: 6,
          userSelect: 'none', transition: 'all 0.2s ease',
        }}
      >
        {errorCount > 0 ? `❌ ${errorCount}` : '🔧 Dev'}
        <span style={{ opacity: 0.5, fontSize: 10 }}>Alt+D</span>
      </div>

      {/* Панель */}
      {visible && (
        <div style={{
          position: 'fixed', bottom: 60, right: 16,
          width: 480, maxHeight: minimized ? 44 : 360,
          zIndex: 9998,
          background: 'rgba(10,10,20,0.97)',
          backdropFilter: 'blur(20px)',
          border: '1px solid rgba(255,255,255,0.12)',
          borderRadius: 16, overflow: 'hidden',
          boxShadow: '0 20px 60px rgba(0,0,0,0.55)',
          transition: 'max-height 0.25s ease',
        }}>
          {/* Заголовок */}
          <div style={{
            display: 'flex', justifyContent: 'space-between', alignItems: 'center',
            padding: '10px 16px',
            borderBottom: minimized ? 'none' : '1px solid rgba(255,255,255,0.08)',
            background: 'rgba(255,255,255,0.04)',
          }}>
            <span style={{ color: 'rgba(255,255,255,0.85)', fontSize: 13, fontWeight: 600 }}>
              🔧 ECA Debug
              {errorCount > 0 && <span style={{ color: '#ef4444', marginLeft: 8 }}>{errorCount} ошибок</span>}
            </span>
            <div style={{ display: 'flex', gap: 8 }}>
              <button onClick={() => setMin(m => !m)}
                style={{ background:'none', border:'none', color:'rgba(255,255,255,0.50)', cursor:'pointer', fontSize:14 }}>
                {minimized ? '⬆' : '⬇'}
              </button>
              <button onClick={() => setLogs([])}
                style={{ background:'none', border:'none', color:'rgba(255,255,255,0.40)', cursor:'pointer', fontSize:11 }}>
                очистить
              </button>
              <button onClick={() => setVisible(false)}
                style={{ background:'none', border:'none', color:'rgba(255,255,255,0.50)', cursor:'pointer', fontSize:16 }}>
                ✕
              </button>
            </div>
          </div>

          {!minimized && (
            <div style={{ overflowY:'auto', maxHeight:300, padding:'6px 0' }}>
              {logs.length === 0 ? (
                <div style={{ textAlign:'center', padding:'20px', color:'rgba(255,255,255,0.28)', fontSize:13 }}>
                  Ошибок нет ✅
                </div>
              ) : logs.map(log => (
                <div key={log.id} style={{
                  padding:'6px 16px',
                  borderBottom:'1px solid rgba(255,255,255,0.04)',
                  display:'flex', gap:8, alignItems:'flex-start',
                }}>
                  <span style={{ fontSize:12, flexShrink:0 }}>{ICONS[log.type]}</span>
                  <div style={{ flex:1, minWidth:0 }}>
                    <div style={{ fontSize:12, color:COLORS[log.type], wordBreak:'break-word', lineHeight:1.4 }}>
                      {log.message}
                    </div>
                    {log.detail && (
                      <div style={{ fontSize:10, color:'rgba(255,255,255,0.30)', marginTop:2 }}>{log.detail}</div>
                    )}
                  </div>
                  <span style={{ fontSize:10, color:'rgba(255,255,255,0.28)', flexShrink:0, fontFamily:'monospace' }}>
                    {log.time}
                  </span>
                </div>
              ))}
            </div>
          )}
        </div>
      )}
    </>
  );
};
