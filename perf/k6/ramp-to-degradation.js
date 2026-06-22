/**
 * P2-7 — Сценарий "ramp-to-degradation": ступенчато поднимает входящий поток ACARS
 * от низкого до заведомо избыточного msg/s, чтобы найти ТОЧКУ ДЕГРАДАЦИИ — нагрузку,
 * после которой latency (p95/p99) или error rate резко растут.
 *
 * Используется executor `ramping-arrival-rate`: k6 пытается поддержать целевой темп
 * запросов независимо от того, сколько времени уходит на ответ (в отличие от
 * VU-driven executor'ов, где рост latency сам снижает фактический RPS и маскирует
 * деградацию). Это важно: если сервис начинает не успевать, мы должны это УВИДЕТЬ
 * в метриках (растущий error_rate/iteration_duration), а не получить тихо более
 * низкий RPS.
 *
 * Запуск (см. docs/perf/README.md):
 *   k6 run perf/k6/ramp-to-degradation.js \
 *     -e BASE_URL=http://localhost:8080 \
 *     -e START_RPS=10 \
 *     -e MAX_RPS=200 \
 *     -e STEP_RPS=20 \
 *     -e STEP_DURATION=1m \
 *     -e FLEET_SIZE=50
 *
 * Переменные окружения:
 *   BASE_URL       — адрес приложения
 *   START_RPS      — стартовая ступень, msg/s (по умолчанию 10)
 *   MAX_RPS        — верхняя ступень, msg/s (по умолчанию 200 — заведомо выше
 *                     ожидаемой "проектной" нагрузки, чтобы точно поймать деградацию)
 *   STEP_RPS       — шаг приращения между ступенями (по умолчанию 20)
 *   STEP_DURATION  — длительность одной ступени (по умолчанию 1m)
 *   FLEET_SIZE     — пул бортов/рейсов (по умолчанию 50)
 *   RAW_RATIO      — доля запросов на /incoming/raw (по умолчанию 0.2)
 *
 * Анализ результата (см. docs/perf/README.md, шаблон отчёта): на графике
 * "RPS ступени -> p95/p99 latency" и "RPS ступени -> error rate" точка деградации —
 * первая ступень, где p95 устойчиво уходит за порог (напр. >1s) ИЛИ error rate
 * заметно отрывается от нуля (см. thresholds ниже — abortOnFail НЕ используется,
 * сценарий должен пройти все ступени и показать форму кривой целиком, а не упасть
 * на первом нарушении).
 */
import http from 'k6/http';
import { check } from 'k6';
import { Rate, Trend } from 'k6/metrics';
import { buildFleet, pickFlight, structuredMessage, rawArincMessage } from './lib/messages.js';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const START_RPS = Number(__ENV.START_RPS || 10);
const MAX_RPS = Number(__ENV.MAX_RPS || 200);
const STEP_RPS = Number(__ENV.STEP_RPS || 20);
const STEP_DURATION = __ENV.STEP_DURATION || '1m';
const FLEET_SIZE = Number(__ENV.FLEET_SIZE || 50);
const RAW_RATIO = Number(__ENV.RAW_RATIO || 0.2);

const fleet = buildFleet(FLEET_SIZE);

export const ingestLatency = new Trend('ingest_latency_ms', true);
export const ingestErrorRate = new Rate('ingest_error_rate');

function buildStages() {
  const stages = [];
  for (let rps = START_RPS; rps <= MAX_RPS; rps += STEP_RPS) {
    stages.push({ target: rps, duration: STEP_DURATION });
  }
  return stages;
}

const stages = buildStages();
const maxRpsInPlan = Math.max(...stages.map((s) => s.target));

export const options = {
  scenarios: {
    ramp: {
      executor: 'ramping-arrival-rate',
      startRate: START_RPS,
      timeUnit: '1s',
      // Запас VU достаточный, чтобы исполнитель сам не стал бутылочным горлышком —
      // если latency растёт, это должно быть видно как медленные ответы реального
      // сервера, а не как нехватка VU у k6.
      preAllocatedVUs: Math.max(50, Math.min(maxRpsInPlan * 3, 1000)),
      maxVUs: Math.max(100, Math.min(maxRpsInPlan * 6, 2000)),
      stages,
    },
  },
  // Намеренно без global thresholds-fail-fast: цель сценария — увидеть ПОЛНУЮ кривую
  // деградации по всем ступеням, а не остановиться на первом нарушении порога.
  // По умолчанию k6 печатает в сводке только p90/p95 — добавляем p99 явно (целевой
  // показатель отчёта, docs/perf/README.md). ВНИМАНИЕ: итоговая сводка агрегирует ВСЕ
  // ступени вместе, поэтому p95/p99 отсюда — это "по всему прогону", не "по ступени";
  // для разбивки по ступеням нужен --out json=results/raw.json и offline-анализ по
  // временным окнам (см. docs/perf/README.md, раздел "Точка деградации: как считать") —
  // либо запуск нескольких отдельных steady-throughput.js на разных TARGET_RPS.
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
    // tags.rps_stage позволяет в k6 cloud/InfluxDB-выводе агрегировать latency по
    // текущей секунде теста и реконструировать "ступень -> latency" даже без ручного
    // деления лога по времени; при выводе через handleSummary агрегируется суммарно
    // по всему прогону — для разбивки по ступеням см. docs/perf/README.md (рекомендация
        // гонять ступени отдельными короткими прогонами steady-throughput.js для точного отчёта).
    tags: { endpoint: useRaw ? 'incoming_raw' : 'incoming' },
  });

  ingestLatency.add(res.timings.duration);
  const ok = check(res, {
    'status is 200': (r) => r.status === 200,
  });
  ingestErrorRate.add(!ok);
}

export function handleSummary(data) {
  // Путь относительный к рабочей директории k6-процесса — см. комментарий в
  // steady-throughput.js и perf/k6/README.md (запускать из директории perf/k6/).
  return {
    stdout: JSON.stringify(data, null, 2),
    'results/ramp-to-degradation-summary.json': JSON.stringify(data, null, 2),
  };
}
