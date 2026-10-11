# Subscription Marketplace

A work-in-progress monolith for a subscription-marketplace SaaS: businesses list
products and manage subscription plans, customers browse a marketplace and
subscribe. The UI is server-rendered with HTMX and backed by a Reitit JSON API
under `/api`.

> **Status:** early work in progress. Several feature modules are still
> scaffolding and only the UI and API route groups are wired up today — see
> [Project status](#project-status).

## What it is

- A single deployable Clojure application (no separate front-end build output to
  serve beyond the stylesheet).
- Server-rendered pages driven by HTMX, so most interactions are plain HTML
  fragments rather than a client-side app.
- A JSON API using Reitit with Malli coercion, exposed under `/api` and
  documented with Swagger UI.
- Server-side sessions backed by Postgres (not cookies), so signing out can
  revoke a session even though the browser still holds the cookie.

## Tech stack

| Concern | Choice |
|---|---|
| Language | Clojure 1.12 |
| HTTP / routing | Ring + Reitit (+ Malli coercion) |
| System / DI | Integrant via [kit](https://kit-clj.github.io/) |
| Database | PostgreSQL, `next.jdbc` + HoneySQL, Ragtime migrations |
| Templating | Hiccup (+ HTMX) |
| CSS | Tailwind CSS v4 + daisyUI (via the standalone CLI) |
| Config | Aero (`resources/system.edn`) |
| Logging | Logback + SLF4J (`clojure.tools.logging`) |
| Build / test | tools.deps CLI, Kaocha |
| Observability | OpenTelemetry Java agent (opt-in) |

## Prerequisites

- **JDK 24** — the project and CI are pinned to 24. Undertow 2.4.x/xnio require
  Java 17+, so anything from 17 up will run, but 24 is what CI uses.
- **Clojure CLI** (`clojure` / `clj`) — <https://clojure.org/guides/install_clojure>
- **clj-kondo** — the linter CI runs. A standalone binary, not a `deps.edn`
  dependency; install it via your package manager or
  <https://github.com/clj-kondo/clj-kondo#installation>.
- **Node.js 22** and npm — for the Tailwind build.
- **Docker** — for the local Postgres instance (and, optionally, the
  observability stack).

## Getting started

```bash
# 1. Postgres (only; the observability stack is opt-in)
docker compose up -d

# 2. Build the stylesheet (generated, gitignored)
npm install
npm run tailwind

# 3. Start the app on http://localhost:3000
clojure -M:dev -m app.core dev
```

Migrations run automatically at boot for the `:dev` and `:test` profiles (see
`:migrate?` in `resources/system.edn`), so a fresh checkout needs no separate
migration step. The development database is `sub_market_test` (the same one the
test suite uses).

### Develop with a REPL

```bash
clojure -M:dev
```

`dev/user.clj` wires up `integrant.repl`, so from the REPL you can use `(go)`,
`(reset)` and `(halt)` to start, reload and stop the system. It also configures
`expound`, tap-based printing and dependency watching.

## Commands

| Task | Command |
|---|---|
| Run the dev server | `clojure -M:dev -m app.core dev` |
| Start a REPL | `clojure -M:dev` |
| Run the test suite | `clojure -X:test` |
| Lint the sources | `clj-kondo --lint src test dev` |
| Check formatting | `clojure -M:check` |
| Fix formatting | `clojure -M:fix` |
| Apply / undo / inspect migrations | `clojure -M:db migrate` · `clojure -M:db rollback [n]` · `clojure -M:db status` |
| Build the stylesheet | `npm run tailwind` |
| Rebuild the stylesheet on change | `npm run tailwind:watch` |
| Scan Clojure deps for CVEs | see [Dependency scanning](#dependency-scanning) |
| Scan npm deps for CVEs | `npm audit --audit-level=high` |
| Run with observability | `clojure -M:dev:otel -m app.core dev` |

## Testing

Tests use [Kaocha](https://github.com/lambdaisland/kaocha) and live under
`test/clj`, mirroring the `src` layout.

```bash
clojure -X:test
```

`tests.edn` enables the `kaocha-junit-xml` plugin, which writes
`target/junit.xml` (with source locations) for the CI test report. Much of the
suite is database-backed and runs against `sub_market_test`; migrations are
applied automatically for the `:test` profile.

View tests assert on hiccup trees rather than rendered HTML strings where
possible — see `test/clj/views/support.clj` for the helpers (`elements`,
`find-by-class`, `within?`, …).

CI additionally gates on linting and formatting (see
[Continuous integration](#continuous-integration)): `clj-kondo --lint src test dev`
and `clojure -M:check`. Run both before pushing.

## Database and migrations

- Schema migrations live in `resources/migrations/*.edn` (Ragtime, applied in
  filename order).
- Connection settings are in `resources/system.edn` under
  `:database.sql/connection` (defaults: `localhost:5432`, db `sub_market_test`,
  user `admin`, password `1234` — matching `docker-compose.yaml`).
- `:migrate?` is `true` for `:dev`/`:test` and `false` otherwise, so production
  schema changes are a deliberate deploy step rather than a boot side effect.
- Use the `:db` CLI for manual control:

  ```bash
  clojure -M:db status        # applied and pending migrations
  clojure -M:db migrate       # apply pending
  clojure -M:db rollback 2    # undo the last two
  ```

## Project structure

```
src/
  app/            System lifecycle (core) and the Ring handler (handler)
  components/     Reusable infrastructure components
    db/           Datasource, migrations and the migration CLI
    stripe/       Stripe integration (stub)
  modules/        Feature modules, one namespace per concern
    auth/         Signup, sign-in, email verification, sessions, crypto, mailer
    users/  vendors/  products/  billing/  subscriptions/  reviews/
    admin/  marketplace/  health
  shared/         Cross-cutting code: config, db helpers, enums,
                  middleware (base/formats/exception), telemetry, utils
  views/          Hiccup views: layout, htmx helpers, auth, dashboard, landing
dev/              Development-only code (never on the production classpath)
  user.clj        REPL helpers (integrant.repl, expound, tap, dep watching)
  observability/  Local OpenTelemetry stack — see its own README
test/clj/         Kaocha tests mirroring the src layout
resources/        system.edn, logback.xml, migrations/, public/
tailwind/         Tailwind entrypoint (input.css)
.github/workflows/  CI: test suite + dependency scan + lint
```

A feature module typically contains `db.clj` (queries), `handlers.clj` (HTTP
handlers) and sometimes `service.clj` (business logic). Routes are declared
centrally in `src/views/routes.clj` (UI) and `src/modules/routes.clj` (API);
the per-module `routes.clj` files are currently empty stubs.

## Observability

An optional local OpenTelemetry stack (collector, Prometheus, Tempo, Loki,
Grafana) lives in `dev/observability/`. Metrics, traces and logs are exported by
attaching the OTel Java agent via the opt-in `:otel` alias:

```bash
./dev/observability/fetch-agent.sh          # once, downloads the agent jar
docker compose --profile observability up -d
clojure -M:dev:otel -m app.core dev
```

Grafana is then at <http://localhost:3001>. Nothing here runs by default and
`:otel` is never part of `:dev`/`:test`. See `dev/observability/README.md` for
the full walkthrough.

## Continuous integration

Three GitHub Actions workflows run on push/PR to `main`/`master`:

- **`run-tests.yml`** — builds the stylesheet, runs `clojure -X:test` against a
  Postgres service container, and publishes `target/junit.xml` as a check run
  with per-test file/line annotations.
- **`dependency-scan.yml`** — scans `deps.edn` with
  [clj-watson](https://github.com/clj-holmes/clj-watson) (GitHub Advisory
  Database) and `package-lock.json` with `npm audit`. It also runs on a weekly
  schedule to catch newly published advisories.
- **`lint.yml`** — runs `clj-kondo --lint src test dev` (any warning fails the
  build; the linter version is pinned to match local runs) and checks formatting
  with the same `clojure -M:check` alias used locally.

All three pin **Temurin JDK 24**. There is intentionally no Dependabot config;
the scheduled scan is the mechanism for surfacing dependency advisories.

### Dependency scanning

The Clojure scan fails on any finding with a CVSS base score of 7 or higher:

```bash
GITHUB_TOKEN=$(gh auth token) \
  clojure -M:clj-watson scan -p deps.edn \
    --database-strategy github-advisory \
    --output stdout-simple \
    --cvss-fail-threshold 7
```

The npm side fails on high/critical findings via `npm audit --audit-level=high`.

## Configuration

Configuration is read from `resources/system.edn` with Aero, selected by profile
(`:dev`, `:test`, `:prod`). Values can be overridden by environment variables:

| Variable | Purpose |
|---|---|
| `HTTP_HOST` | Server host (default `localhost`) |
| `BASE_URL` | Base URL used to build email verification links |
| `SUB_MARKET_LOG_LEVEL` | Root log level (the `:dev` alias defaults it to `DEBUG`) |
| `MAILGUN_API_KEY` / `MAILGUN_DOMAIN` / `MAILGUN_FROM` / `MAILGUN_REGION` | Mailgun delivery; outside `:dev`/`:test` the key and domain are required |
| `OTEL_EXPORTER_OTLP_ENDPOINT` / `OTEL_SERVICE_NAME` | Point the OTel agent somewhere other than the local collector |

## Project status

Early, in-progress. The auth flow (signup, sign-in, email verification, sessions)
and the vendor product listing are the most complete paths. Most other feature
modules (billing, subscriptions, reviews, admin, marketplace) are scaffolding:
`src/modules/*/routes.clj` are empty stubs and `src/components/stripe/core.clj`
is a placeholder. Persistent state is Postgres only — there is no Redis or job
queue yet.
