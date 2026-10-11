# AGENTS.md

Guidance for AI coding agents working in this repository. It assumes familiarity
with Clojure; see [README.md](README.md) for human-oriented setup and a fuller
tour of the project.

## Environment invariants

- **Java 24** is the pinned runtime (CI uses Temurin 24). Undertow 2.4.x and
  xnio require **Java 17+**, so do not downgrade below 17.
- Several versions in `deps.edn` are **deliberate security pins**, not arbitrary
  bumps. Do not change them without a reason, and re-run the scan afterwards:

  | Dependency | Pinned | Why |
  |---|---|---|
  | `undertow-core` | 2.4.4.Final | CVE-2025-12543, CVE-2026-3260 |
  | `xnio-api` / `xnio-nio` | 3.8.17.Final | CVE-2023-5685 |
  | `bcprov` / `bcpkix` / `bcutil` | 1.86 | BouncyCastle family kept in lockstep |
  | `jackson-core` / `jackson-databind` | 2.22.3 | CVE-2026-89407 |
  | `postgresql` | 42.7.14 | CVE-2026-54291 |
  | `logback-classic` | 1.6.5 | patches low-severity findings |

  Some of these are transitive (jackson via reitit/jsonista, BouncyCastle via
  buddy); they are pinned at the top level so the patched version wins over what
  a transitive POM asks for.

## Commands

Run these from the repository root.

| Task | Command |
|---|---|
| Test suite | `clojure -X:test` |
| Format check (must pass) | `clojure -M:check` |
| Format fix | `clojure -M:fix` |
| Migrations | `clojure -M:db migrate` / `rollback [n]` / `status` |
| Build stylesheet | `npm run tailwind` |
| Postgres only | `docker compose up -d` |
| Dev server | `clojure -M:dev -m app.core dev` |
| Dependency scan | `GITHUB_TOKEN=$(gh auth token) clojure -M:clj-watson scan -p deps.edn --database-strategy github-advisory --output stdout-simple --cvss-fail-threshold 7` |

Always run `clojure -X:test` and `clojure -M:check` before declaring work done.
The suite is database-backed and expects Postgres on `localhost:5432` (the
`sub_market_test` database); `docker compose up -d` provides it.

## Architecture map

- **System lifecycle** — `app.core` (`start-app` / `stop-app` / `-main`), managed
  by Integrant. Configuration comes from `shared.config/system-config`, which
  reads `resources/system.edn` through `kit.config` and selects a profile.
- **Config / profiles** — `resources/system.edn` defines the Integrant graph.
  Aero `#profile` selects `:dev`, `:test` or `:prod`; `#env` reads environment
  variables. Prefer adding a component + a `#ig/ref` over reaching for globals.
- **Server** — `kit.edge.server.undertow` provides `:server/http`. The Ring
  handler is built in `app.handler` as `:handler/ring`: a Reitit ring-handler
  with redirect-trailing-slash, a static resource handler, Swagger UI at
  `:api-path` and a default 404/405/406 handler.
- **Routing** — `:router/core` builds the router from `:router/routes`, which is
  an `#ig/refset :reitit/routes`. Route groups register themselves by
  `(derive :reitit.routes/<name> :reitit/routes)` and a matching `ig/init-key`.
  Only two groups exist today: `:reitit.routes/api` (`src/modules/routes.clj`)
  and `:reitit.routes/ui` (`src/views/routes.clj`). In `:dev`, `:router/core`
  returns a function so routes reload without restarting the whole system.
- **Middleware** — `shared.middleware.core/wrap-base` applies Ring site defaults
  and attaches a `:system` map (`{:db … :mailer … :config {:base-url …
  :dev-verify? …}}`) to every request, *inside* the defaults chain so sessions
  and params are parsed first. Handlers read `(get-in request [:system …])`.
- **Database** — `components.db.core/datastore` builds every datasource and sets
  `next.jdbc`'s builder to `as-unqualified-lower-maps`. Always go through it:
  without the builder, result keys are namespaced by table and `(:id row)` is
  silently `nil`. Migrations live in `components.db.migrations` and run at boot
  only when `:migrate?` is true (`:dev`/`:test`).

## Conventions

- **Feature modules** live in `src/modules/<name>/` and follow a de-facto shape:
  `db.clj` (queries, via `components.db.core`), `handlers.clj` (HTTP handlers),
  and `service.clj` when there is logic worth isolating. Note that several
  modules (`billing`, `admin`, `reviews`, `subscriptions`, `users`, `vendors`,
  `marketplace`) are stubs — check before assuming a module is real.
- **Routes are centralised**: add UI routes in `src/views/routes.clj` and API
  routes in `src/modules/routes.clj`. The per-module `routes.clj` files are
  currently empty and unused.
- **Handlers return Ring responses** using `ring.util.http-response`
  (`ok`, `bad-request`, `not-found`, …).
- **Views are hiccup vectors**, not HTML strings. Prefer asserting on the tree;
  `test/clj/views/support.clj` has the helpers (`elements`, `find-by-class`,
  `within?`, `classes-in`, `hrefs`, `ids-in`, `css-selector`).
- **Comments explain *why*, not *what*.** The codebase consistently documents
  intent and the failure mode a choice avoids; match that style. Do not add
  narration that just restates the code.
- **`dev/` must never be required from `src/`.** `dev/` is not on the production
  classpath; a `src` namespace requiring a `dev` one makes the app unbootable
  outside development. This has regressed before (see the comments in
  `app.core` and `shared.middleware.core`).
- Namespaces are kebab-case and hyphen-free in file names where the module
  already is (e.g. `modules.auth.mailer` → `src/modules/auth/mailer.clj`).

## Gotchas

- **Stylesheet is generated.** `resources/public/output.css` is gitignored. If it
  is missing, `core-test` fails (the page 404s the stylesheet) and
  `views/stylesheet-test` silently registers no tests. Run `npm run tailwind`
  before testing. CI builds it before the suite.
- **Logging** is Logback 1.6.x / SLF4J 2.x. `resources/logback.xml` and
  `test/clj/logback-test.xml` use only stable appender/encoder APIs.
- **Java version**: undertow 2.4.x will not load on Java 11/8
  (`UnsupportedClassVersionError`). If tests fail at
  `ring/adapter/undertow/headers.clj`, check `java -version` first.
- **No Dependabot.** Dependency advisories are handled by the weekly
  `dependency-scan.yml` workflow, not by automated dependency PRs. Do not add a
  Dependabot config.
- **`:build` alias is dangling.** `deps.edn` has a `:build` alias pointing at a
  `build` namespace, but there is no `build.clj`. Don't rely on it.
- **npm overrides** in `package.json` pin `@parcel/watcher` to 2.6.0 to drop a
  vulnerable transitive chain; keep it until the devDependency graph no longer
  needs it.

## Testing and commits

- Tests live in `test/clj` mirroring `src`; put new tests next to their peers.
  DB-backed tests use the `:test` profile and `sub_market_test`.
- `tests.edn` enables the `kaocha-junit-xml` plugin (`target/junit.xml`) for CI;
  it is harmless locally and `target/` is gitignored.
- Commit subjects are short and capitalised (`Add product page on vendor
  dashboard`, `Bumped project to Java 24 …`). Match that style; keep the subject
  to the essential change.
