/**
 * P2-7 — Сценарий "идемпотентность под нагрузкой" (duplicate-storm): одновременно
 * шлёт МНОГО копий одного и того же сообщения (один externalMessageId на группу
 * VU) под конкурентной нагрузкой, чтобы проверить, что P2-1 идемпотентность
 * по externalMessageId держится и под гонкой, а не только в одиночных
 * unit/integration-тестах (см. MessagePersistenceTransactionTest,
 * EventProcessorServiceTest — там идемпотентность проверяется последовательно).
 *
 * Ожидаемое поведение системы: все запросы получают HTTP 200 (повтор не должен
 * валиться ошибкой), но НА КАЖДЫЙ externalMessageId должна быть создана РОВНО ОДНА
 * запись `messages` и ОПУБЛИКОВАНО РОВНО ОДНО `NormalizedEvent` (т.е. движок не
 * запускает последовательность повторно). Этот скрипт проверяет только "сторону
 * клиента" (контракт ответа + отсутствие ошибок под конкурентным дублированием) —
 * фактическую гарантию "ровно один messageId" нужно сверить ПОСЛЕ прогона по БД
 * (см. docs/perf/README.md, раздел "Проверка идемпотентности после прогона": SQL
 * запрос на дубликаты external_message_id и количество строк sequence_instance).
 *
 * Запуск:
 *   k6 run perf/k6/duplicate-storm.js \
 *     -e BASE_URL=http://localhost:8080 \
 *     -e GROUPS=20 \
 *     -e COPIES_PER_GROUP=25 \
 *     -e ITERATIONS=5
 *
 * Переменные окружения:
 *   BASE_URL          — адрес приложения
 *   GROUPS            — сколько разных externalMessageId (= разных "дублируемых
 *                        сообщений") гоняем параллельно, по умолчанию 20
 *   COPIES_PER_GROUP  — сколько одновременных копий каждого сообщения шлём в каждой
 *                        итерации (VU на группу), по умолчанию 25
 *   ITERATIONS        — сколько раз повторить весь шторм дублей подряд (по умолчанию 5) —
 *                        проверяет идемпотентность не только "в моменте", но и при
 *                        повторных приходах того же сообщения позже (ретраи ACARS-гейтвея)
 *
 * Метрика duplicate_response_ok должна быть pass-rate 1.0 — любой статус кроме 200
 * на повторе считается дефектом идемпотентного приёма (если найдено — задача
 * bug-fixer/sequence-engine-dev, P1-7/P2-1).
 */
import http from 'k6/http';
import { check } from 'k6';
import { Rate } from 'k6/metrics';
import { buildFleet } from './lib/messages.js';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const GROUPS = Number(__ENV.GROUPS || 20);
const COPIES_PER_GROUP = Number(__ENV.COPIES_PER_GROUP || 25);
const ITERATIONS = Number(__ENV.ITERATIONS || 5);

const fleet = buildFleet(GROUPS);

export const duplicateResponseOk = new Rate('duplicate_response_ok');

export const options = {
  scenarios: {
    duplicateStorm: {
      executor: 'per-vu-iterations',
      vus: GROUPS * COPIES_PER_GROUP,
      iterations: ITERATIONS,
      maxDuration: '5m',
    },
  },
  thresholds: {
    duplicate_response_ok: ['rate==1.0'],
    http_req_failed: ['rate<0.01'],
  },
};

export default function () {
  // groupIndex детерминированно делит VU на GROUPS групп по COPIES_PER_GROUP штук —
  // все VU внутри одной группы шлют ОДИНАКОВЫЙ externalMessageId одновременно.
  const groupIndex = Math.floor((__VU - 1) / COPIES_PER_GROUP) % GROUPS;
  const flight = fleet[groupIndex];
  // externalMessageId фиксирован НА ИТЕРАЦИЮ (не на VU) — все COPIES_PER_GROUP VU
  // одной группы и при повторных ITERATIONS бьют ровно в тот же идентификатор,
  // это и есть "шторм дублей" одного сообщения.
  const externalMessageId = `K6-DUP-${flight.tail}-iter${__ITER}`;

  const payload = {
    messageType: 'DOWNLINK',
    templateName: 'STATUS',
    aircraftId: flight.tail,
    flightNumber: flight.flight,
    metadataJson: JSON.stringify({ source: 'k6-duplicate-storm' }),
    externalMessageId,
  };

  const res = http.post(`${BASE_URL}/api/v1/messages/incoming`, JSON.stringify(payload), {
    headers: { 'Content-Type': 'application/json' },
    tags: { endpoint: 'incoming_duplicate' },
  });

  const ok = check(res, {
    'duplicate accepted with 200 (idempotent, not an error)': (r) => r.status === 200,
  });
  duplicateResponseOk.add(ok);
}

export function handleSummary(data) {
  // Путь относительный к рабочей директории k6-процесса — см. комментарий в
  // steady-throughput.js и perf/k6/README.md (запускать из директории perf/k6/).
  return {
    stdout: JSON.stringify(data, null, 2),
    'results/duplicate-storm-summary.json': JSON.stringify(data, null, 2),
  };
}
