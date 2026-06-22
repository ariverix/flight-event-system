/**
 * Генераторы реалистичных payload'ов ACARS-сообщений для нагрузочных сценариев (P2-7).
 *
 * Используются обоими целевыми эндпоинтами:
 *  - POST /api/v1/messages/incoming      (структурированный путь, P2-1)
 *  - POST /api/v1/messages/incoming/raw  (сырые форматы ARINC/Type B/AFTN, P2-2)
 *
 * Парк бортов/рейсов смоделирован так, чтобы при выбранном числе VU получить заданное
 * количество ОДНОВРЕМЕННО АКТИВНЫХ рейсов (см. docs/perf/README.md, раздел "Параметры
 * нагрузки"): каждый VU закреплён за одним бортом/рейсом из пула на весь iteration-цикл
 * через `__VU`, а сам пул бортов конфигурируется через FLEET_SIZE.
 */

// Демо-борт из CLAUDE.md/ROADMAP.md — всегда первый в пуле, остальные сгенерированы по
// тому же паттерну реестрационных номеров RA/VP, чтобы выглядело правдоподобно.
const DEMO_TAIL = 'VP-BQR';
const DEMO_FLIGHT = 'SU1234';

const TAIL_SUFFIXES = [
  'BQR', 'BAB', 'BFE', 'BIH', 'BLT', 'BNB', 'BQG', 'BQT', 'BZH', 'BPS',
  'BWE', 'BLA', 'BIS', 'BCT', 'BDU', 'BEK', 'BFG', 'BGN', 'BHR', 'BJC',
];

const CARRIER_CODES = ['SU', 'U6', 'DP', 'FV', 'N4'];

const MESSAGE_TYPES = ['DOWNLINK', 'UPLINK', 'GROUND'];

const DOWNLINK_TEMPLATES = ['STATUS', 'POSITION_REPORT', 'OOOI', 'FUEL_REPORT'];
const UPLINK_TEMPLATES = ['CLEARANCE', 'WEATHER', 'FREE_TEXT'];
const GROUND_TEMPLATES = ['HANDLING_NOTICE', 'CREW_MESSAGE'];

/** Детерминированный псевдо-парк бортов размером fleetSize (минимум 1 — демо-борт). */
export function buildFleet(fleetSize) {
  const size = Math.max(1, fleetSize | 0);
  const fleet = [{ tail: DEMO_TAIL, flight: DEMO_FLIGHT }];
  for (let i = 1; i < size; i++) {
    const suffix = TAIL_SUFFIXES[i % TAIL_SUFFIXES.length];
    const carrier = CARRIER_CODES[i % CARRIER_CODES.length];
    const flightNo = 1000 + i;
    fleet.push({ tail: `VP-${suffix}`, flight: `${carrier}${flightNo}` });
  }
  return fleet;
}

/** Закрепляет VU за бортом из пула — один VU = один "активный рейс" на всю длительность теста. */
export function pickFlight(fleet, vu) {
  const idx = (vu - 1) % fleet.length;
  return fleet[idx];
}

function templateFor(messageType) {
  if (messageType === 'DOWNLINK') return DOWNLINK_TEMPLATES[Math.floor(Math.random() * DOWNLINK_TEMPLATES.length)];
  if (messageType === 'UPLINK') return UPLINK_TEMPLATES[Math.floor(Math.random() * UPLINK_TEMPLATES.length)];
  return GROUND_TEMPLATES[Math.floor(Math.random() * GROUND_TEMPLATES.length)];
}

/**
 * Структурированное сообщение для /api/v1/messages/incoming.
 *
 * @param flight {tail, flight} закреплённая за VU пара борт/рейс
 * @param uniqueId уникальный суффикс (vu-iteration-timestamp), гарантирует уникальный
 *        externalMessageId в штатном (не дублирующем) режиме нагрузки
 * @param forceDuplicateId если задан — переопределяет externalMessageId тем же значением
 *        для всех вызовов (сценарий проверки идемпотентности под нагрузкой, см. duplicate-storm.js)
 */
export function structuredMessage(flight, uniqueId, forceDuplicateId) {
  const messageType = MESSAGE_TYPES[Math.floor(Math.random() * MESSAGE_TYPES.length)];
  const externalMessageId = forceDuplicateId || `K6-${flight.tail}-${uniqueId}`;
  return {
    messageType,
    templateName: templateFor(messageType),
    aircraftId: flight.tail,
    flightNumber: flight.flight,
    metadataJson: JSON.stringify({ source: 'k6-load-test', vu: uniqueId }),
    externalMessageId,
  };
}

/**
 * Сырое ARINC 618 сообщение для /api/v1/messages/incoming/raw — формат
 * "AN/<tail> FI/<flight> LABEL/H1 TEXT", реально разбираемый Arinc618Parser
 * (см. backend/src/test/.../RawMessageParserServiceTest.java, dispatchesArinc618).
 * Достаточно для нагрузочной цели (throughput/latency конвейера raw-пути), не для
 * проверки полноты парсинга форматов — это покрыто unit/integration-тестами парсера.
 */
export function rawArincMessage(flight, uniqueId) {
  const body = `AN/${flight.tail} FI/${flight.flight} LABEL/H1 MSGREF/K6-${uniqueId} TEXT STATUS REPORT`;
  return {
    format: 'ARINC_618',
    rawMessage: body,
    departureAirport: 'UUEE',
    arrivalAirport: 'ULLI',
    flightDate: null,
  };
}
