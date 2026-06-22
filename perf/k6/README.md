# k6-скрипты нагрузочного тестирования входящего ACARS-потока (P2-7)

Методика, целевые показатели и шаблон отчёта — в [`docs/perf/README.md`](../../docs/perf/README.md).
Этот файл — краткая шпаргалка по запуску.

## Файлы

| Файл | Сценарий |
|---|---|
| `steady-throughput.js` | Устойчивый поток на целевом RPS (контрольная точка latency/error rate) |
| `ramp-to-degradation.js` | Ступенчатый рост RPS до точки деградации |
| `duplicate-storm.js` | Идемпотентность приёма под конкурентной нагрузкой (дубли `externalMessageId`) |
| `lib/messages.js` | Общие генераторы payload'ов (структурированный + raw ARINC 618 путь) |

## Установка k6

Windows: `winget install k6` или `choco install k6`, либо скачать бинарь с https://k6.io/docs/get-started/installation/
Linux CI-раннер (если будет отдельная perf-джоба): `sudo gpg -k && sudo gpg --no-default-keyring --keyring /usr/share/keyrings/k6-archive-keyring.gpg --keyserver hkp://keyserver.ubuntu.com:80 --recv-keys C5AD17C747E3415A3642D57D77C6C491D6AC1D69 ...` — см. официальную инструкцию выше, либо `docker run -i grafana/k6 run - < script.js`.

## Быстрый запуск (стенд поднят локально, `docker-compose up --build`)

Все команды — из директории `perf/k6/` (так относительный путь `results/...` в
`handleSummary` каждого скрипта пишет JSON-сводку именно в `perf/k6/results/`):

```bash
cd perf/k6
k6 run steady-throughput.js -e BASE_URL=http://localhost:8080 -e TARGET_RPS=20 -e DURATION=2m
k6 run ramp-to-degradation.js -e BASE_URL=http://localhost:8080 -e START_RPS=10 -e MAX_RPS=200 -e STEP_RPS=20
k6 run duplicate-storm.js -e BASE_URL=http://localhost:8080 -e GROUPS=20 -e COPIES_PER_GROUP=25
```

Через Docker (если k6 не установлен локально, но Docker есть) — проверено в этом репозитории
(Windows + Docker Desktop, без k6 binary в PATH):

```bash
cd perf/k6
docker run --rm -v "$(pwd):/scripts" -w /scripts grafana/k6 run /scripts/steady-throughput.js \
  -e BASE_URL=http://host.docker.internal:8080 -e TARGET_RPS=20 -e DURATION=2m
```

На Linux с `--network host` можно использовать `BASE_URL=http://localhost:8080` напрямую;
на Windows/Mac Docker Desktop `--network host` не поддерживается так, как на Linux —
обязательно `host.docker.internal`, иначе соединение не достигнет хоста.

`-v "$(pwd):/scripts"` в Git Bash на Windows может конвертировать путь неожиданно — если
получаете ошибку "couldn't be found on local disk" с путём вида `C:/Program Files/Git/...`,
выставьте `MSYS_NO_PATHCONV=1` перед `docker run` (см. также `docs/perf/README.md`).

Результаты (`handleSummary`) пишутся в `perf/k6/results/*.json` (директория в `.gitignore` — это
артефакты конкретного прогона на конкретном стенде, не часть исходников; в отчёт `docs/perf/`
переносятся итоговые числа, а не сырой JSON).
