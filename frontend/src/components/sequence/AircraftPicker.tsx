/**
 * AircraftPicker (Фаза 6) — выбор борта (tail number) для привязки последовательности.
 *
 * Борта берутся из журнала сообщений через GET /api/v1/aircraft (Фаза 5). В системе нет
 * отдельного реестра бортов и типа ВС — выбираем именно tail number (AN), которым
 * последовательность привязывается к борту (SITA-паритет). Поиск серверный (debounced),
 * строгая типизация из OpenAPI-контракта, i18n RU/EN, доступность (aria-label).
 */
import { useCallback, useEffect, useRef, useState } from 'react';
import { Select, Spin } from 'antd';
import { aircraftApi, type AircraftSummary } from '../../api/aircraftApi';
import { useEditorI18n } from '../../i18n/useEditorI18n';

interface AircraftPickerProps {
  value?: string | null;
  onChange: (tailNumber: string | undefined) => void;
  disabled?: boolean;
  /** id для связи с внешним <label> (a11y). */
  id?: string;
}

const SEARCH_DEBOUNCE_MS = 300;
const PAGE_SIZE = 20;

interface AircraftOption {
  value: string;
  label: string;
}

function toOption(a: AircraftSummary, lastSeenLabel: string, flightsLabel: string): AircraftOption {
  const tail = a.aircraftId ?? '';
  const seen = a.lastSeen ? new Date(a.lastSeen).toLocaleString() : '—';
  const flights = a.flightCount ?? 0;
  return { value: tail, label: `${tail} · ${lastSeenLabel}: ${seen} · ${flights} ${flightsLabel}` };
}

export function AircraftPicker({ value, onChange, disabled, id }: AircraftPickerProps) {
  const d = useEditorI18n();
  const [options, setOptions] = useState<AircraftOption[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState(false);
  const debounceRef = useRef<ReturnType<typeof setTimeout> | undefined>(undefined);
  const reqIdRef = useRef(0);

  const fetchAircraft = useCallback(
    async (search: string): Promise<void> => {
      const reqId = ++reqIdRef.current;
      setLoading(true);
      setError(false);
      try {
        const page = await aircraftApi.list({ search, page: 0, size: PAGE_SIZE });
        // игнорируем ответ устаревшего запроса (гонка при быстром вводе)
        if (reqId !== reqIdRef.current) return;
        const content = page.content ?? [];
        setOptions(content.map((a) => toOption(a, d.aircraftLastSeen, d.aircraftFlights)));
      } catch {
        if (reqId !== reqIdRef.current) return;
        setError(true);
        setOptions([]);
      } finally {
        if (reqId === reqIdRef.current) setLoading(false);
      }
    },
    [d.aircraftLastSeen, d.aircraftFlights],
  );

  // первичная загрузка списка
  useEffect(() => {
    void fetchAircraft('');
    return () => {
      if (debounceRef.current) clearTimeout(debounceRef.current);
    };
  }, [fetchAircraft]);

  const handleSearch = useCallback(
    (text: string): void => {
      if (debounceRef.current) clearTimeout(debounceRef.current);
      debounceRef.current = setTimeout(() => {
        void fetchAircraft(text);
      }, SEARCH_DEBOUNCE_MS);
    },
    [fetchAircraft],
  );

  return (
    <Select
      id={id}
      aria-label={d.aircraftPickerLabel}
      showSearch
      allowClear
      disabled={disabled}
      value={value ?? undefined}
      placeholder={d.aircraftPickerPlaceholder}
      filterOption={false}
      onSearch={handleSearch}
      onChange={(v: string | undefined) => onChange(v ?? undefined)}
      notFoundContent={
        loading ? (
          <span>
            <Spin size="small" /> {d.aircraftPickerSearching}
          </span>
        ) : error ? (
          d.aircraftPickerError
        ) : (
          d.aircraftPickerEmpty
        )
      }
      options={options}
      style={{ width: '100%' }}
    />
  );
}
