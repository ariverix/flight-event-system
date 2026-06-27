/**
 * P6-3 — Приёмочный нагрузочный прогон. Расширяет steady-throughput.js (P2-7):
 * добавляет именованный вывод через LABEL, чтобы хранить несколько прогонов без
 * ручного переименования артефактов. Замеряет p50/p95/p99 входящего ACARS-потока.
 *
 * Запуск (из директории perf/k6):
 *   k6 run p6-3-acceptance.js \
 *     -e BASE_URL=http://localhost:8080 \
 *     -e TARGET_RPS=100 \
 *     -e DURATION=2m \
 *     -e FLEET_SIZE=50 \
 *     -e LABEL=before-rps100
 *
 * Переменные:
 *   BASE_URL    — адрес приложения (default: http://localhost:8080)
 *   TARGET_RPS  — целевой RPS (default: 100)
 *   DURATION    — длительность плато (default: 2m)
 *   FLEET_SIZE  — число бортов в пуле (default: 50)
 *   RAW_RATIO   — доля raw-пути (default: 0.2)
 *   LABEL       — суффикс для файла результата (default: run)
 */
import http from 'k6/http';
import { check } from 'k6';
import { Rate, Trend, Counter } from 'k6/metrics';
import { buildFleet, pickFlight, structuredMessage, rawArincMessage } from './lib/messages.js';

const BASE_URL  = __ENV.BASE_URL   || 'http://localhost:8080';
const TARGET_RPS = Number(__ENV.TARGET_RPS  || 100);
const DURATION  = __ENV.DURATION   || '2m';
const FLEET_SIZE = Number(__ENV.FLEET_SIZE || 50);
const RAW_RATIO  = Number(__ENV.RAW_RATIO  || 0.2);
const LABEL      = __ENV.LABEL     || 'run';

const fleet = buildFleet(FLEET_SIZE);

export const ingestLatency   = new Trend('ingest_latency_ms', true);
export const ingestErrorRate = new Rate('ingest_error_rate');
export const totalRequests   = new Counter('total_requests');

export const options = {
  scenarios: {
    steady: {
      executor: 'constant-arrival-rate',
      rate: TARGET_RPS,
      timeUnit: '1s',
      duration: DURATION,
      preAllocatedVUs: Math.max(20, Math.min(FLEET_SIZE * 2, 300)),
      maxVUs:          Math.max(50, Math.min(FLEET_SIZE * 5, 600)),
    },
  },
  thresholds: {
    http_req_failed:  ['rate<0.01'],
    'ingest_latency_ms': ['p(95)<1000', 'p(99)<2000'],
  },
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'p(99)'],
};

export default function () {
  const flight   = pickFlight(fleet, __VU);
  const uniqueId = `${__VU}-${__ITER}-${Date.now()}`;
  const useRaw   = Math.random() < RAW_RATIO;

  const url     = useRaw
    ? `${BASE_URL}/api/v1/messages/incoming/raw`
    : `${BASE_URL}/api/v1/messages/incoming`;
  const payload = useRaw
    ? rawArincMessage(flight, uniqueId)
    : structuredMessage(flight, uniqueId);

  const res = http.post(url, JSON.stringify(payload), {
    headers: { 'Content-Type': 'application/json' },
    tags:    { endpoint: useRaw ? 'incoming_raw' : 'incoming', label: LABEL },
  });

  ingestLatency.add(res.timings.duration);
  totalRequests.add(1);
  const ok = check(res, {
    'status is 200': (r) => r.status === 200,
  });
  ingestErrorRate.add(!ok);
}

export function handleSummary(data) {
  const filename = `results/p6-3-${LABEL}.json`;
  return {
    stdout: JSON.stringify(data, null, 2),
    [filename]: JSON.stringify(data, null, 2),
  };
}
