/**
 * P2-7 — Сценарий "steady throughput": входящий поток ACARS держится на целевом
 * msg/s в течение фиксированного окна, чтобы измерить latency (p50/p95/p99) и
 * error rate БЕЗ роста нагрузки (контрольная точка перед ramp-to-degradation.js).
 *
 * Бьёт по структурированному пути POST /api/v1/messages/incoming (P2-1) и, опционально,
 * по сырому POST /api/v1/messages/incoming/raw (P2-2) — пропорция задаётся RAW_RATIO.
 * Каждое сообщение получает уникальный externalMessageId/MSGREF — это НЕ тест
 * идемпотентности (для дублей см. duplicate-storm.js), цель — честный throughput
 * по новым сообщениям, как в проде.
 *
 * Запуск (см. docs/perf/README.md для полной методики):
 *   k6 run perf/k6/steady-throughput.js \
 *     -e BASE_URL=http://localhost:8080 \
 *     -e TARGET_RPS=50 \
 *     -e DURATION=3m \
 *     -e FLEET_SIZE=30 \
 *     -e RAW_RATIO=0.2
 *
 * Переменные окружения (все опциональны, значения по умолчанию — консервативная
 * стартовая точка для дев-стенда):
 *   BASE_URL    — адрес приложения, по умолчанию http://localhost:8080
 *   TARGET_RPS  — целевой устойчивый throughput, сообщений/сек (по умолчанию 20)
 *   DURATION    — длительность плато на целевом RPS (по умолчанию 2m)
 *   FLEET_SIZE  — размер пула бортов/рейсов, по умолчанию 20 (= "кол-во одновременных
 *                 активных рейсов" для целей этого замера)
 *   RAW_RATIO   — доля запросов на /incoming/raw вместо /incoming, 0..1 (по умолчанию 0.2)
 */
import http from 'k6/http';
import { check } from 'k6';
import { Rate, Trend } from 'k6/metrics';
import { buildFleet, pickFlight, structuredMessage, rawArincMessage } from './lib/messages.js';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const TARGET_RPS = Number(__ENV.TARGET_RPS || 20);
const DURATION = __ENV.DURATION || '2m';
const FLEET_SIZE = Number(__ENV.FLEET_SIZE || 20);
const RAW_RATIO = Number(__ENV.RAW_RATIO || 0.2);

const fleet = buildFleet(FLEET_SIZE);

// Кастомные метрики, дублирующие встроенные http_req_duration/http_req_failed под
// именем, явно привязанным к доменному смыслу (входящий ACARS-конвейер), чтобы в
// отчёте (docs/perf/README.md) не путать с латентностью прочих эндпоинтов, если
// тест когда-нибудь расширят.
export const ingestLatency = new Trend('ingest_latency_ms', true);
export const ingestErrorRate = new Rate('ingest_error_rate');

export const options = {
  scenarios: {
    steady: {
      executor: 'constant-arrival-rate',
      rate: TARGET_RPS,
      timeUnit: '1s',
      duration: DURATION,
      // preAllocatedVUs/maxVUs — запас, чтобы executor не "захлёбывался" под латентностью;
      // FLEET_SIZE задаёт количество одновременных рейсов, не количество VU исполнителя.
      preAllocatedVUs: Math.max(20, Math.min(FLEET_SIZE * 2, 200)),
      maxVUs: Math.max(50, Math.min(FLEET_SIZE * 4, 400)),
    },
  },
  thresholds: {
    // Целевые показатели P2-7 (см. docs/perf/README.md, раздел "Целевые показатели") —
    // пороги намеренно мягкие на дев-стенде; ужесточаются после первого реального прогона.
    http_req_failed: ['rate<0.01'],
    'ingest_latency_ms': ['p(95)<1000', 'p(99)<2000'],
  },
  // По умолчанию k6 печатает в сводке только p90/p95 — добавляем p99 явно, т.к. это один
  // из целевых показателей отчёта (docs/perf/README.md).
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'p(99)'],
};

export default function () {
  const flight = pickFlight(fleet, __VU);
  const uniqueId = `${__VU}-${__ITER}-${Date.now()}`;

  const useRaw = Math.random() < RAW_RATIO;
  const url = useRaw
    ? `${BASE_URL}/api/v1/messages/incoming/raw`
    : `${BASE_URL}/api/v1/messages/incoming`;
  const payload = useRaw ? rawArincMessage(flight, uniqueId) : structuredMessage(flight, uniqueId);

  const res = http.post(url, JSON.stringify(payload), {
    headers: { 'Content-Type': 'application/json' },
    tags: { endpoint: useRaw ? 'incoming_raw' : 'incoming' },
  });

  ingestLatency.add(res.timings.duration);
  const ok = check(res, {
    'status is 200': (r) => r.status === 200,
  });
  ingestErrorRate.add(!ok);
}

export function handleSummary(data) {
  // Путь относительный к рабочей директории k6-процесса. При запуске из корня репозитория
  // (k6 run perf/k6/steady-throughput.js) — это perf/k6/results/...; при запуске в Docker
  // с -v "$(pwd)/perf/k6:/scripts" -w /scripts — это просто results/... (см. perf/k6/README.md).
  return {
    stdout: JSON.stringify(data, null, 2),
    'results/steady-throughput-summary.json': JSON.stringify(data, null, 2),
  };
}
