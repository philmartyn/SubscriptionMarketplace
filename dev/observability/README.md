# Local observability (metrics, traces + logs)

Optional development stack for looking at SubMarket's metrics, traces and logs
in Grafana. It is deliberately small:

```
Clojure app (host, :otel alias)
  └─ OTel Java agent  --OTLP :4318-->  otel-collector (container)
                                          ├─ traces  --> tempo:4317
                                          ├─ metrics --> prometheus exporter :8889
                                          │                          ▲ scrape
                                          └─ logs    --> loki:3100
Prometheus (container) <───────────────────────────────────────────┘
Grafana (container) ── queries ──> Prometheus (metrics), Tempo (traces), Loki (logs)
```

Nothing here runs by default. A plain `docker compose up` still starts only
Postgres, and the app is unchanged unless you add the `:otel` alias. With it the
agent also exports logs, so every `tools.logging` call lands in Loki as a
structured record (severity, service, environment, trace/span id). The console
appender is untouched: stdout stays human-readable.

## Layout

| Path | What it is |
|---|---|
| `fetch-agent.sh` | Downloads the pinned OTel Java agent here (gitignored) |
| `otel-collector.yaml` | Collector: OTLP in; Tempo (traces), a Prometheus scrape endpoint (metrics) and Loki (logs) out |
| `prometheus.yaml` | Scrapes the collector, and itself for health |
| `tempo.yaml` | Single-binary Tempo, local storage |
| `loki.yaml` | Single-binary Loki, local storage, structured metadata enabled |
| `grafana/provisioning/` | Datasources and the dashboard provider, loaded at startup |
| `grafana/dashboards/` | The starter "SubMarket overview" dashboard |

## Bring it up

```bash
# 1. Agent jar, once (~26 MB, gitignored)
./dev/observability/fetch-agent.sh

# 2. The stack: collector, Prometheus, Tempo, Loki, Grafana
docker compose --profile observability up -d

# 3. The app, with the agent attached.
#    A one-shot server on port 3000:
clojure -M:dev:otel -m app.core dev

#    ...or a REPL, then boot the system from it:
#      clojure -M:dev:otel
#      user=> (go)        ; dev/user.clj already sets the :dev profile
```

The agent's default OTLP endpoint is `http://localhost:4318`, which is where the
collector publishes, so no endpoint configuration is needed locally. Override
with `OTEL_EXPORTER_OTLP_ENDPOINT` / `OTEL_SERVICE_NAME` when pointing somewhere
else (for example Grafana Cloud).

`-M:dev:otel` concatenates the aliases' JVM options, so the agent is attached on
top of the usual `:dev` settings. It is never part of `:dev` or `:test`, because
a missing jar would stop every JVM from starting and the test suite must run
with no collector reachable.

| Service | URL | Credentials |
|---|---|---|
| Grafana | http://localhost:3001 | admin / admin |
| Prometheus | http://localhost:9090 | — |
| Tempo | http://localhost:3200 | — |
| Loki | http://localhost:3100 | — |
| Collector metrics (debug) | http://localhost:8889/metrics | — |

Grafana is on **3001** because the app already uses 3000.

## What to look at

- **Dashboard** — "SubMarket overview" (provisioned under the Dashboards tab):
  HTTP RED, JVM heap and GC, and the auth counters.
- **Explore → Tempo** — traces for `{resource.service.name="submarket"}`. An
  HTTP request span should have **child JDBC spans** for its queries, which is
  also the quickest proof that instrumentation is live.
- **Explore → Prometheus** — raw metric names, for example
  `http_server_request_duration_seconds_count`, `jvm_memory_used_bytes`,
  `auth_signup_total`.
- **Explore → Loki** — logs for `{service_name="submarket"}`. Stream labels are
  the resource attributes (`service_name`, `deployment_environment`); the rest of
  the record (severity, logger, thread, `trace_id`, `span_id`) is stored as
  structured metadata, queryable without a JSON parser. A log line's **View
  trace** link opens its Tempo trace, and a span's **Logs for this span** link
  comes back the other way.

Metric names are translated on the way out of the collector (dots become
underscores, counters gain `_total`, histograms gain `_bucket`/`_sum`/`_count`),
so the dashboard queries are a starting point — confirm against Explore before
relying on a name.

## Turn it off

```bash
docker compose --profile observability down       # keep metrics, traces and logs
docker compose --profile observability down -v    # also drop the volumes
```

The app keeps running without the stack; the agent just fails to export.

## Notes

- **Version alignment matters.** The agent (pinned in `fetch-agent.sh`) bundles
  the OTel API; `io.opentelemetry/opentelemetry-api` in `deps.edn` must match
  the SDK that release targets (v2.32.0 → 1.66.0). If they diverge, the agent's
  API bridge is skipped and custom spans/counters silently no-op.
- **Route names.** Undertow gives no route template, so `http.route` is the raw
  path. A reitit-aware middleware could fix that later; not needed for "basic".
- **Undertow.** The agent's docs list Undertow 1.4+; we run `undertow-core`
  2.3.12. If no server span appears, add a small Ring middleware span instead.
