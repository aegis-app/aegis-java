# AEGIS (Java)

| Item | Value |
| ---- | ----- |
| Version | 1.0.0 |
| Status | Rebuilt (functional parity with the previous implementation) |
| Runs on | Any host with JDK 21 (Windows / macOS / Linux) |
| Implementation | Java 21, standard library only, plain `javac` build (no Gradle/Maven) |

日本語版: [README.ja.md](README.ja.md) / Design notes: [DESIGN.md](DESIGN.md)

AEGIS (Java) is a disaster-information client in the Aegis family. It
receives earthquake, EEW (Earthquake Early Warning), tsunami, weather
warning, and typhoon information from **A.A.S (A2S)** — the family's
alert hub — and delivers briefings and notifications. Weather forecasts
(JMA / Open-Meteo), NHK news, and rain radar are fetched directly, as
before.

## Features

- **Briefing (`now`)** — current quakes, EEW, tsunami, warnings,
  typhoon, weather for the configured locations, and top news in one
  message, with a 📌 summary section.
- **Resident monitoring (`watch`)** — polls A2S (quake 20s / EEW 10s /
  weather 600s by default) and keeps an SSE connection to
  `GET /v1/stream` for low-latency events; notifies on new or revised
  events only (deduplicated, with an EEW upward-revision re-notify
  rule). Scheduled briefings at the configured times (default
  07:00 / 21:00).
- **Web dashboard (`serve`)** — http://127.0.0.1:8765/ with
  `/api/briefing` and `/api/history`, auto-refreshing every 60s.
- **Natural-language Q&A (`ask`)** — answers questions such as
  「大宮の天気は？傘は必要？」 from the latest fetched data.
- **Notifications** — console and `data/alerts.log` always; Discord
  webhook, a generic webhook (Event JSON), and desktop notifications
  (Windows toast / macOS / Linux) when configured.
- **History (`history`)** — past notifications stored in
  `data/history.tsv` (10,000-line cap with rotation).
- **Self-diagnostics (`test`)** — checks the A2S connection and every
  data source; exit code 0/1. `test-notify` sends one test event
  through all configured channels.

## A2S contract

- REST: `GET /v1/events` (`kind` / `since` / `limit`), `GET /v1/status`
- SSE: `GET /v1/stream` with `Authorization: Bearer <token>`; events
  received before the `ready` frame are treated as replay (state sync
  only, never notified); duplicates are dropped by key + `updatedAt`;
  reconnects back off 5s → 10s → 30s → 60s.
- The token is read **only** from `config.json` (`a2s_url` /
  `a2s_token`) or the `A2S_URL` / `A2S_TOKEN` environment variables
  (environment wins). No token is ever written in source code.
- Without A2S configured, quake / EEW / tsunami / warning / typhoon
  information is not available (weather, news, and radar still work).

## Quick start

```bash
./build.sh            # full build: compile + unit tests + aegis.jar (Windows: build.bat)
./run.sh unittest     # unit tests only (352 checks, network-free; also run by build.sh)
./run.sh test         # self-diagnostics (connects to the real sources)
./run.sh test-notify  # send one test notification
./run.sh now          # briefing right now
./run.sh watch        # resident monitoring (Ctrl+C to stop)
./run.sh serve        # web dashboard http://127.0.0.1:8765/
./run.sh ask "大宮の天気は？傘は必要？"
./run.sh history      # notification history
```

With a built jar, `java -jar aegis.jar now` works too.

## Configuration (`config.json`)

Same format as the Python AEGIS. See `config.example.json`. Locations,
notification threshold (`notify_min_severity`), `quake_min_scale`,
polling intervals, briefing times, and webhook URLs are configurable.
`config.json` is git-ignored because it contains secrets (the A2S
token and webhook URLs).

## Tests

Unit tests live in `src/test/java/aegis/test/` (`AegisTest`,
`A2sTest`; main-method style, no JUnit — the project has zero
dependencies). `FixtureCheck` is a manual tool that parses real
captured JSON payloads when they are placed in a fixtures directory;
it skips gracefully when none are present.

## License

MIT — see [LICENSE](LICENSE).
