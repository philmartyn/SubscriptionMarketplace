# SubMarket — marketplace roadmap (first pass)

Status: first pass — decisions locked; payments revised to **Stripe Connect (platform-as-MoR)**. Implementation not started.
Project: `/Users/philmartyn/IdeaProjects/SubMarket` (Clojure, kit-clj + integrant + reitit, hiccup + htmx + daisyUI/Tailwind, Postgres via next.jdbc + honey.sql).

Goal: turn the current single-sided skeleton (auth + vendor product listings) into a **two-sided subscription marketplace**:

- **Vendors** list recurring products, get paid out, and see the subscribers and money attributable to them.
- **Subscribers** discover products, subscribe and pay, then view, manage and cancel their subscriptions.
- Payments run through **Stripe Connect**, with the platform acting as **merchant of record** and carrying the tax liability, while a **Stripe filing partner** registers, files and remits tax on the platform's behalf — so vendors are relieved of sales-tax compliance and the platform buys the compliance work à la carte instead of paying an MoR's blended fee.

This is a living document: it is the first pass and is expected to grow (spike detail, per-phase acceptance criteria, schema detail).

---

## Confirmed decisions

| # | Area | Decision |
|---|---|---|
| 1 | Catalog | **Physical goods + services** (coffee, produce boxes, vinyl, classes), recurring |
| 2 | Fulfillment mode | Per product: `delivery` / `collection` / `service` |
| 3 | Fulfillment ↔ billing | **Billing-driven** — each paid cycle = one fulfilment; "skip" defers the next cycle |
| 4 | Shipping cost | **Vendor-set, charged at checkout** (only for `delivery`) |
| 5 | Vendor locations | **One location per vendor** (Phase 1) |
| 6 | Commerce scope | **Subscriptions only** — "bought in store" means `collection`, not one-off purchases |
| 7 | Payments provider | **Stripe Connect (Express) + Stripe Tax + a Stripe filing partner** |
| 8 | Merchant of record | **Platform** (destination charges, **without** `on_behalf_of`) |
| 9 | Tax | Platform is **liable**; Stripe Tax calculates and collects; the filing partner registers/files/remits; **vendors are relieved of sales-tax compliance**; collected tax is **withheld from vendor payouts** |
| 10 | Connect onboarding | **Express** — Stripe-hosted KYC and dashboard, plus **platform approval** before listing/payouts |
| 11 | Local / in-store | **Collection of online orders only** — no POS/Terminal payment path |
| 12 | Monetisation | **Commission / take-rate** via `application_fee_amount`; free to list; **global default rate + per-vendor override** |
| 13 | Checkout | **Stripe hosted checkout**; guest subscribe-first; account created/claimed post-purchase; address captured **only for delivery** |
| 14 | Vendor visibility | **Fulfillment-scoped + own financials** (name, shipping address, delivery notes, gross/fee/net); server-side ownership scoping; **no cross-vendor exposure**; contact masked/proxied |
| 15 | Refunds & disputes | **Platform-side** (platform balance is debited) |
| 16 | Background work | **Postgres-backed job queue now**, behind an interface so an external queue can replace it later |
| 17 | Reviews | In scope, **Phase 3** |
| 18 | Marketing copy | Treated as placeholder; rewritten to match the model later |

---

## Architecture implications

- **Stripe is the system of record for billing.** Renewal scheduling, tax, and dunning are Stripe's job (Stripe Billing + Tax). Locally, `subscriptions` and `payments` are a **webhook-maintained mirror / read model**, not a billing engine.
- **The platform is the merchant of record.** The customer buys from SubMarket, not the vendor; vendors are suppliers/fulfillment partners. Statement descriptor, terms, refunds and chargebacks are platform-side, and disputes debit the platform balance.
- **Destination charges.** Funds settle on the platform account and transfer to the connected account; the platform keeps its take via `application_fee_amount`. The collected tax must be **withheld** from the vendor transfer and remitted by the platform.
- **Provider-agnostic naming.** Generic `provider` + external IDs are retained so the PSP can change without reshaping the schema; the current implementation targets Stripe.
- **Money breakdown.** `payments` carries **gross / Stripe fee / platform fee / tax / net**, feeding the vendor dashboard and payout reconciliation.
- **Idempotent ingress.** A `provider_events` table records inbound Stripe webhooks so retries and duplicates are safe; side effects dispatch through the job queue.
- **Async infra.** A `jobs` table + a lightweight worker wired into Integrant, behind an interface so it can be swapped for Redis/SQS later.
- **Authorization.** Existing **vendor** guards are joined by **subscriber** guards, and every subscriber/financial query is **tenant-scoped** to the owning vendor.
- **Data minimisation.** The vendor visibility model is enforced server-side, backed by a vendor DPA and an anti-disintermediation stance.

---

## Workstreams

### A. Financial spine & Stripe integration
- Stripe Connect **Express** account onboarding + KYC; **platform approval** gate.
- Destination-charge checkout and subscriptions; `application_fee_amount`; **tax withheld from the vendor transfer**.
- **Stripe Tax** enablement (`liability[type]=self`), tax-code mapping from the catalog, platform tax **registrations**, and the **filing/remittance partner** integration.
- Webhook ingress (verified, idempotent); subscription lifecycle mirroring; refunds and disputes; vendor payout statements; **reconciliation job** for provider↔DB drift.
- Config/secrets and **Stripe test mode**.

### B. Subscriber experience
- Discovery: browse + search + filters (incl. location).
- Product/plan detail with interval (and, where relevant, quantity) selection.
- Hosted checkout; post-purchase account provisioning.
- My subscriptions: status, next charge/delivery, amount.
- Billing history, invoices/receipts.
- Self-service: cancel, pause, skip, resume, plan/quantity change.
- Notifications: receipt, renewal reminder, failed payment/dunning, cancellation.

### C. Vendor experience
- Onboarding: Stripe Connect **Express** KYC + **platform approval**; store details + one location.
- Catalog: pricing per interval/currency, **tax code** (classification), fulfillment mode, shipping config (for delivery), availability/publish.
- Subscriber roster (fulfillment-scoped) + own financials (gross/fee/net).
- Payouts view.
- Notifications: new subscriber, payout, failed payment.

### D. Fulfillment
- Address capture (delivery only) + later edits; per-subscription shipping address.
- Collection at the vendor's single location (online orders only).
- Service attendance/redemption.
- Billing-driven cycles; skip defers the next cycle.
- Capacity / oversell protection.
- Delivery notifications.

### E. Platform admin / ops / compliance
- Vendor approval queue; suspend/feature vendors.
- Commission overrides (per-vendor).
- **Tax operations:** registration tracking, Stripe Tax reports, filing-partner liaison.
- Refund/dispute handling; support tooling.
- GDPR: export/delete; vendor DPA.
- Anti-disintermediation hardening.

### F. Cross-cutting
- Authz: subscriber guards, ownership scoping.
- Async infrastructure (`jobs` table + worker).
- Testing: Stripe test mode, Stripe CLI, recorded webhook fixtures, contract tests.
- Observability/alerting on webhook failures and payment drift.

---

## Phases

### Phase 0 — Foundations, Stripe Connect & tax stack
- **Stripe Connect + tax multi-vendor design spike**: destination charges, Express onboarding, subscriptions with `application_fee_amount`/`transfer_data`, Stripe Tax liability, withholding tax from transfers, refunds/disputes, multi-currency, payout schedule. Decision memo.
- **Tax compliance setup** (must be operational at first sale, not deferred): platform registrations, Stripe Tax configuration, tax-code mapping, filing-partner onboarding.
- Schema spine (see workstream A).
- `:payments/provider` component, config/secrets, webhook ingress.
- Postgres job queue + worker.
- Subscriber auth guards.

### Phase 1 — End-to-end happy path (one vendor, one product)
1. Vendor onboards (Connect Express KYC + platform approval), creates a product + one plan (tax code, fulfillment mode, location, shipping if delivery).
2. Plan/price synced to Stripe.
3. Buyer discovers the product, picks a plan, checks out via **hosted checkout** (address only for delivery).
4. Webhook mirrors `subscription` + `payment` (incl. tax); guest purchase provisions/claims an account.
5. Subscriber sees the subscription, next charge, and can cancel; gets a receipt/invoice.
6. Vendor sees the subscriber (fulfillment-scoped) with gross/fee/net financials.

### Phase 2 — Lifecycle
Pause/skip/resume, plan/quantity changes, refunds, dunning, receipt/renewal/cancellation notifications.

### Phase 3 — Marketplace
Search & filters (incl. location), vendor storefronts, **reviews/ratings**, vendor analytics, admin/ops, notification breadth.

### Phase 4 — Ops & compliance
Reconciliation hardening, payout reporting, GDPR, tax-registration edge cases, anti-disintermediation hardening.

---

## Payments flow (end-to-end)

Grounded in the locked choices: Stripe Connect **Express**, platform-as-**MoR** via **destination charges**, Stripe **Billing + Tax** + filing partner, **hosted checkout**, **guest subscribe-first**.

### 0. Platform setup (one-time, before any vendor)
Platform/Stripe account with Connect enabled; Stripe Tax enabled with **platform registrations** and `liability[type]=self`; filing partner connected; webhook endpoint + secrets; platform statement descriptor, terms, refund policy; commission default rate.

### 1. Vendor sign-on / onboarding
1. Vendor applies → **platform approval** gate.
2. Platform creates a **Stripe Connect Express** account; Stripe hosts **KYC/onboarding** (business details, payout bank, ToS).
3. Stripe enables capabilities (`charges`, `transfers`, payouts); account moves `restricted` → `verified`.
4. Platform stores `provider_account_id` + status; **listing/payout gated** on approval + a capable account.
5. Platform is MoR, so the vendor configures **no tax** — they only classify products (tax code).

### 2. Product & plan upload (catalog → Stripe price)
1. Vendor creates a product + plan: price, currency, **interval**, **tax code**, **fulfillment mode** (`delivery`/`collection`/`service`), shipping (delivery only), location.
2. Platform creates the matching Stripe **Product/Price** on the **platform account** (platform is MoR — *not* `on_behalf_of`).
3. Publish → discoverable. Commission and destination are applied per subscription, not per price.

### 3. Discovery & checkout (user purchase)
1. Subscriber picks a plan; guest checkout (no account required yet).
2. Platform creates a Stripe **Checkout Session** (`mode=subscription`) carrying:
   - the plan's price and interval;
   - `subscription_data.transfer_data[destination]` = the vendor's connected account;
   - `subscription_data.application_fee_percent` = **platform commission**;
   - `automatic_tax[enabled]=true`, `automatic_tax[liability][type]=self` (**platform liable**);
   - email + `shipping_address_collection`/shipping options **only for delivery**;
   - metadata (`vendor_id`, `product_id`, `plan_id`) and success/cancel URLs.
3. Subscriber pays on **Stripe's hosted page**; Stripe creates Customer + Subscription; **first invoice paid**.

### 4. Post-purchase provisioning
1. Webhooks (`checkout.session.completed`, `customer.subscription.created`, `invoice.paid`) → platform:
   - find/create an account by email and send a **claim/magic link** (guest → account);
   - write the local **subscription mirror** (provider IDs, lifecycle, period dates, interval, quantity);
   - write the **payment/invoice** row (gross / Stripe fee / platform fee / tax / net);
   - create the **fulfillment entitlement**.
2. Send receipt/welcome.

### 5. Recurring billing (Stripe-owned)
1. Stripe Billing renews each period → invoice → payment (**no platform scheduler**).
2. `invoice.paid` → append payment mirror, **reset entitlement/quota**, notify.
3. `invoice.payment_failed` → dunning/retries → `past_due` → notify/grace/expire.
4. Tax recalculated per invoice; **collected tax stays with the platform** to remit.

### 6. Fulfillment
Delivery / collection / service; record the handover on the vendor **Collect page** (manual + short code now); the entitlement ledger decrements.

### 7. Payouts
1. Funds settle on the **platform** account (destination charges).
2. Platform **transfers** to the vendor's connected account **minus the application fee** and — critically — **minus the tax** (set the transfer amount to exclude collected tax, or the tax follows the money to the vendor).
3. Stripe pays out to the vendor's bank on its schedule; `transfer.*` / `payout.*` webhooks mirror **payout statements** for the vendor dashboard.

### 8. Refunds & disputes
- **Refund:** platform initiates (MoR); reverses the transfer and claws back the application fee; updates mirrors.
- **Chargeback:** **platform balance is debited**; platform handles evidence; optionally **recover from the vendor** via a transfer reversal. Webhooks: `charge.dispute.*`, `refund.*`.

### 9. Cancellation & lifecycle
Cancel (at period end vs immediate), pause/resume, skip (defer next cycle), plan/quantity change (proration) — driven by Stripe, mirrored by `customer.subscription.updated`/`deleted`.

### 10. Tax filing & remittance
Stripe Tax reports → **filing partner** registers/files/remits; the platform tracks registrations, thresholds and remittance. Runs continuously from first sale.

### 11. Reporting & reconciliation
Vendor statements (gross/fee/tax/net/payout), platform commission totals, and a **reconciliation job** comparing Stripe balance transactions against local mirrors, repairing drift.

---

## Payments lifecycle checklist (the parts beyond the happy path)

| Concern | What it covers |
|---|---|
| **Onboarding & capability gating** | KYC states, restricted accounts, re-verification, approval before listing |
| **Tax setup** | registrations, tax-code mapping, thresholds, filing partner |
| **Catalog/price sync** | product/plan → Stripe product/price, updates, archiving |
| **Checkout** | hosted session, shipping only when delivery, currency; promo/trial later |
| **Provisioning** | guest→account claim, subscription/payment mirrors, entitlements |
| **Recurring billing** | renewals, proration, quantity changes, paused collection |
| **Dunning & recovery** | failed payments, retries, grace, expiry, recovery |
| **Payouts & transfers** | transfer amounts, tax withholding, payout schedule, statements |
| **Refunds & disputes** | reversals, fee clawback, evidence, vendor recovery |
| **Cancellation & lifecycle** | self-service cancel/pause/skip/resume |
| **Commission** | default rate + per-vendor override, application fee, reporting |
| **Tax filing/remittance** | reports, filing, remittance, jurisdiction tracking |
| **Reconciliation** | Stripe vs local ledger drift, payout vs invoice matching |
| **Webhooks & idempotency** | verified, deduped events; job queue for side effects |
| **Config & secrets** | API keys, webhook secret, test vs live mode |
| **Observability** | webhook failures, payment drift, alerting |
| **Testing** | Stripe test mode, Stripe CLI, recorded webhook fixtures |

### Key Stripe objects & events
- **Objects:** Connected account (Express), Customer, Product/Price, Checkout Session, Subscription, Invoice, PaymentIntent/Charge, Transfer, Payout, Balance Transaction, Tax Calculation/Transaction, Dispute.
- **Events:** `account.updated`, `checkout.session.completed`, `customer.subscription.created/updated/deleted`, `invoice.paid`/`payment_failed`, `charge.refunded`, `charge.dispute.created/closed`, `transfer.*`, `payout.*`.

### Gotchas
- **Tax withholding on transfers** is the easiest thing to get wrong — collected tax must not reach the vendor.
- **Guest checkout → account claim** must be transactional with the subscription mirror.
- Refunds must **reverse the transfer and claw back the application fee**.
- **Disputes hit the platform**, not the vendor — decide the recovery policy.
- Everything must be **idempotent** and dispatched through the job queue.

---

## Alternatives considered (payments)

The payments approach was deliberately re-evaluated. The current choice is **Stripe Connect + Stripe Tax + a Stripe filing partner, platform-as-MoR**. The alternatives below are retained as fallbacks / future options.

### A. MoR marketplace provider (original approach)
- **What it is:** a third party (e.g. Whop, Paddle, FastSpring, Cleverbridge, Digital River/ExpandNow, 2Checkout/Verifone, PayPro Global) is the merchant of record and carries the tax liability end-to-end; vendors never touch tax.
- **Appeal:** maximum liability offload for both platform and vendors; single onboarding.
- **Why not chosen:** most headline MoRs are digital-goods-first and a poor fit for **physical** subscription goods and **collection** commerce; marketplace MoR with vendor **payouts** is uncommon; blended fees (~5%+) are higher than platform-as-MoR with outsourced filing.
- **Reconsider if:** we drop physical goods, or compliance cost/effort proves higher than an MoR's blended fee.

### B. Stripe Managed Payments (Stripe as MoR)
- **What it is:** Stripe itself is the merchant of record and handles tax compliance, checkout, fraud and disputes.
- **Why not chosen:** less platform control and higher cost than Connect + Tax + filing partner; the goal is to keep control while outsourcing only the compliance work.

### C. Vendor-as-MoR (direct charges, or destination + `on_behalf_of`)
- **What it is:** the connected account is the merchant of record and is liable for tax.
- **Appeal:** pushes tax liability onto vendors; closest to the MoR benefit while on Stripe.
- **Why not chosen:** it pushes compliance *onto vendors* (the opposite of the pitch to attract business); marketplace-facilitator / deemed-supplier laws may still oblige the platform; and with `on_behalf_of` the platform still covers negative balances.

### D. Avalara end-to-end (AvaTax + Registrations + Managed Returns)
- **What it is:** Avalara replaces Stripe Tax for calculation and handles registration/filing/remittance.
- **Why not chosen:** a second vendor and integration; staying inside Stripe's ecosystem is simpler. Viable if scale or jurisdiction coverage demands it.

### E. Stripe Tax calculation only (defer filing/remittance)
- **What it is:** launch calculating with Stripe Tax, handle registration/filing manually, choose a filing partner later.
- **Why not chosen:** a fallback only — tax filing obligations exist from first sale, so a partner was selected up front.

---

## Future: in-store cash-to-subscription (deferred-start)

Status: future / considered — not Phase 1. A vendor upsell to revisit once the core marketplace is live.

Idea: a vendor converts a **walk-in, cash single purchase** into a recurring subscription. The customer pays cash in store; the vendor enrols them into a subscription whose billing begins on the **next cycle**, processed normally by Stripe.

### Approach
- The initial cash sale is a **vendor-owned retail transaction the platform never touches** — not listed, ordered, or paid for through SubMarket. The platform forgoes commission and tax liability on it by design.
- The subscription is a **separate, platform-mediated relationship** that begins with its **first Stripe-billed cycle**. The platform is MoR (and Stripe Tax applies) only from that first charge.
- Implementation is small and additive:
  - **Deferred-start subscription** (Stripe `start_date` / `billing_cycle_anchor` / trial): billing starts at the next cycle.
  - **Vendor in-store enrolment flow**: capture customer (email/phone) + card, hand off to Stripe hosted setup; account claimed like guest checkout.
  - **No offline-payment ledger row**, **no platform-side scheduler**, and **no forked tax model** — every billed cycle is a normal Stripe charge.

### Boundary condition (must hold)
The platform must **not facilitate** the initial cash sale: no platform listing, checkout, recorded payment, or use of the platform's in-store tool as the POS for that sale. If it *were* a platform order paid in cash, marketplace-facilitator / deemed-supplier rules and the commission model would attach.

### Caveats
- **Deterministic billing start** — fixed by rule (at enrolment or next cycle), not vendor-extendable, to avoid gaming.
- **Don't book the interim period as a platform sale** — goods supplied before the first charge belong to the vendor's own sale.
- **The vendor remains the retailer** for the store sale and its tax.
- **Confirm with tax counsel** — facilitator / deemed-supplier rules are jurisdiction-specific.
- **Keep the platform POS out of the initial sale** — otherwise facilitation is reintroduced.

### Why it's attractive
The walk-in sale is the warmest possible lead. The vendor does the selling, the platform captures the recurring revenue, and the pitch is clean: *"we don't take a cut of your walk-in sale; we just take over the recurring revenue."*

### Dropped alternative
A per-cycle or prepaid cash model — recording offline cash payments against platform-tracked subscriptions — was considered and set aside: it reintroduces commission-on-cash mechanics, platform-side tax collection and remittance for sales Stripe never processed, a platform-side renewal scheduler, and cash-payment integrity controls.

### Relationship to current decisions
Extends decision 11 ("Collection of online orders only") and would slot in as a **Phase 2–3** vendor capability.

---

## In-store collection / redemption recording

Status: design spec — manual capture ships with fulfillment; the quota model is planned.

Context: a `collection` or `service` product's fulfilment is handed over in person, so we need to record that the handover happened. The customer **presents** an identifier; the **vendor** records the handover. The customer never self-marks (except possibly ultra-low-value items, flagged as a maybe).

### Entitlement model
- Quota-shaped from the start: **N redemptions per period**, where periodic products are simply the **N = 1** case. Modelling it this way now means the later quota feature is a config change, not a schema migration.

### Capture
- **Now:** a vendor-facing **Collect page** with **manual entry + short code**.
- **Later (same page):** QR/camera capture and handheld-scanner (keyboard-wedge) capture.

### Token strategy (per product / interval / value)
- High-value, periodic → **one-time per-cycle signed code**.
- Low-value, high-frequency → **static membership token + quota**.
- Per-product config with a platform default.

### Missed collections
- **Carry-over entitlement:** uncollected redemptions stay redeemable in later periods, capped at **N per period**; anything beyond N is forfeited. No money moves.
- **N is per-product with a platform default.**

### Security
- Signed codes; short TTL and/or one-time use.
- **Idempotent** marking; **audit log** (who / when / where / method); rate limiting; ownership scoping so a vendor can only collect their own subscribers.

### Likely data-model shape
- Plan config: redemption period, allowance per period, carry-over cap (`N`), token strategy.
- Per-subscription **entitlement ledger**: allowance, used, carried over, forfeited, per period.
- **Redemptions** log: subscription, timestamp, vendor/location, method (code/manual), operator, idempotency key.

### Deferred / to nail down
- Quota-per-period products (the "coffee a day" case).
- Catch-up collections (redeem several at once); early/late redemption; offline queuing.
- What "period" means for quota vs the billing cycle (they may differ).
- QR/camera capture and handheld-scanner capture.

### Relationship to decisions
Extends **decision 2** (fulfillment modes) and refines **decision 3** — "each paid cycle = one fulfilment" holds for N = 1 and becomes "up to N redemptions per cycle" for services.

---

## UI inventory

Grounded in the locked decisions. Everything below is new unless noted.

### What exists today
Landing page (`views.landing`), auth (`views.auth` — signin/signup, verification), layout/nav (`views.layout`), vendor dashboard shell (`views.dashboard.vendor`), **vendor product CRUD** (`views.products`), subscriber dashboard shell (`views.dashboard.home`).

### Subscriber (buyer) UI

| Surface | Key elements | Phase |
|---|---|---|
| Discovery / browse | Search box; filters (category, cadence, price, **location**, fulfillment mode, availability); results grid of **plan cards** (image, title, vendor, price/interval, cadence, pickup/delivery, rating); sort; empty/no-results states | 3 (basic in 1) |
| Product / plan detail | Images, description, vendor; **interval selector**, quantity, **tax-inclusive price**; fulfillment block (delivery vs collection, location, delivery areas, shipping cost); **Subscribe** CTA; reviews (later) | 1 |
| Vendor storefront | Vendor branding, location, their plans, rating | 3 |
| Checkout handoff | Stripe-hosted (not ours) + our **success page** ("check your email to activate") and **cancel/return** page | 1 |
| Account claim | Magic-link landing + set-password form (guest → account) | 1 |
| My subscriptions | List of subscription cards (product, vendor, status badge, next charge/delivery, amount, interval); empty state | 1 |
| Subscription detail | Plan + price breakdown (tax incl.); schedule (next charge, next delivery/collection); billing history; actions: **pause / skip / resume / cancel / change plan / quantity**; payment-method link (Stripe portal); shipping address | 1 (actions 2) |
| Billing / invoices | Invoice list + detail/download/receipt | 1 (view), 2 (full) |
| Collection / entitlement | What's due, quota used, collection history | 2 |
| Profile & addresses | Email/password/profile; shipping addresses (per-subscription) | 1 (basic) / 2 |
| Notification preferences | Email/push settings | 3 |

### Vendor UI

| Surface | Key elements | Phase |
|---|---|---|
| Vendor application | Business form (details, category, location); **approval status** page | 1 |
| Connect onboarding | Stripe-hosted (redirect/embedded) + our **status/return** page ("verifying your details") | 1 |
| Vendor dashboard | Overview: today's collections, new subscribers, revenue, next payout, alerts (failed payments, onboarding banners) | 1 (basic) / 2 |
| Catalog | Product list (exists) + create/edit; **plans/prices** (interval, price, currency); **tax code**; **fulfillment mode**; **shipping config** (delivery); location; publish/unpublish; image URL | 1 |
| Subscribers roster | List + search; subscriber detail: **own financials** (gross/fee/net), status, next charge, delivery/collection schedule, **masked/proxied contact** | 1 (basic) / 2 |
| Collect page (in-store) | **Manual short-code entry**, customer **search**, **due-today list**, confirm handover, receipt; **mobile/tablet-first** | 1 |
| Payouts & financials | Payout list, statements, fees, tax, refunds/disputes | 2 |
| Settings | Location, business info, commission display, notification prefs | 2 |

### Platform / admin UI (internal)

| Surface | Key elements | Phase |
|---|---|---|
| Admin dashboard | Vendor **approval queue** (approve/suspend/feature); **commission override** per vendor; all transactions; **refunds/disputes** tooling; tax-registration status; reconciliation view | 3–4 |

### Shared components & primitives
Built once, reused everywhere: **plan/subscription card**; **status badge** (subscription, KYC, payout, dispute); **price display** (tax-inclusive, currency); **interval selector**; **quantity stepper**; **data table** with sort/filter/pagination; **filter panel**; **search input**; **form + field errors** (existing pattern); **confirmation modal** (cancel/refund); **flash/toast**; **banner**; **empty state**; htmx loading indicators; **address form**; **date/schedule display**; money/percentage formatting; navigation shells (public, subscriber, vendor, admin).

### Transactional email (UI outside the app)
Welcome/verify; **receipt/invoice**; renewal reminder; **dunning/failed payment**; cancellation confirmation; **account-claim magic link**; vendor: new subscriber / payout / failed payment.

### Phase 1 minimum set (happy path)
Landing tweak → product detail + interval selector → Stripe checkout handoff + success/cancel → account-claim → **My subscriptions** list + detail (view/cancel) → vendor **application + Connect status** → **catalog create/edit** with fulfillment mode → **subscribers list** with financials → **Collect page** (manual/short code) → receipts + welcome/claim emails.

### Notable UX decisions to make
1. **Discovery layout** — grid vs list; how prominent location/pickup filtering is (basic in Phase 1, rich in Phase 3).
2. **Collect page device** — phone/tablet browser (camera later) vs desktop + handheld scanner; mobile-first either way.
3. **Tax display** — always tax-inclusive in the UI, with the breakdown at checkout/invoices.
4. **Vendor subscriber detail** — the shape of masked contact (masked email vs proxy messaging).
5. **Guest checkout onboarding** — how hard we push account creation post-purchase.
6. **Deferred-start enrolment** (future) — the vendor in-store "enrol customer + set up card" screen.

---

## Backend inventory

Grounded in the current stack (kit-clj/Integrant, reitit, next.jdbc + honey.sql, migrations in `resources/migrations`).

### What exists today
Auth module (full); `products` module (DB + handlers + service); `shared` (config, db, middleware, enums, telemetry); `components/db` (core, migrations, cli); a **stub** `components.stripe`; and **stub** modules `billing`, `subscriptions`, `reviews`, `admin`, `marketplace`, `users`, `vendors`. Routes are centralised (`src/modules/routes.clj` API, `src/views/routes.clj` UI); migrations run 001–004, so the next is **005**.

### Data model (migrations 005+)

| Table | Purpose | Notes |
|---|---|---|
| `vendors` (extend) | Connect account + approval + economics | `provider_account_id`, connect status, approval status, `commission_rate_override`, location/address |
| `accounts` (extend) | Stripe customer mapping | `provider_customer_id` |
| `product_prices` (new) | One row per interval price | `interval`, `amount`, `currency`, `stripe_price_id`, `active` |
| `products` (extend) | Selling config | `tax_code`, `fulfillment_mode`, `status` (draft/published/archived), shipping fields / delivery regions |
| `subscriptions` (extend/new) | Platform mirror + entitlement | provider ids, lifecycle status, `current_period_start/end`, `cancel_at_period_end`, `paused_until`, interval, quantity, fulfillment snapshot |
| `invoices` / `payments` (extend) | Money + tax breakdown | provider/external id, gross / Stripe fee / platform fee / tax / net, period, status |
| `payouts` (new), `transfers` (new) | Vendor payout + per-transaction transfer records | Stripe payout/transfer ids, arrival date, status |
| `refunds` (new), `disputes` (new) | Refund/dispute records | status, amounts, evidence, vendor recovery |
| `provider_events` (new) | Idempotent webhook log | Stripe `event.id`, type, payload, processed_at |
| `jobs` (new) | Background queue | type, payload, `run_at`, attempts, status, `locked_at` |
| `entitlements` (new) | Per-subscription quota ledger | allowance, used, carried over, forfeited, per period |
| `redemptions` (new) | Collection/handover records | subscription, timestamp, vendor/location, method, operator, idempotency key |
| `addresses` (new) | Shipping addresses | per account / per subscription |
| `tax_registrations` (new) | Platform registrations | jurisdiction, status, provider registration id |
| `settings` (new) | Platform defaults | commission default, credit-threshold default |

### Components (Integrant)
- **`:payments/provider`** — Stripe client + config (secret/publishable keys, webhook secret, mode). Replaces/absorbs `components.stripe`. Methods: create Checkout Session, create/update/archive Price, refund, cancel/pause subscription, retrieve account status, verify webhook.
- **`:jobs/worker`** — polls `jobs`, executes handlers with backoff, concurrency + locking; behind an interface so an external queue can replace it later.
- **`:payments/tax`** config (tax codes, filing-partner settings).
- Wired via `system.edn` with `#env` / `#ig/ref`.

### Feature modules (services)
- **`vendors`** — application + approval; create/link Connect Express account; sync `account.updated`; payout readiness.
- **`products`** — plans/prices, **tax code**, **fulfillment mode**, shipping; sync Product/Price to Stripe.
- **`marketplace`** (search) — discovery queries: text, category, cadence, price, location, fulfillment, availability.
- **`subscriptions`** — create from checkout; lifecycle mirror; pause/skip/resume/cancel; plan/quantity change; entitlement.
- **`billing`/`payments`** — Checkout Session creation; invoice/payment mirror; refunds; disputes; payout statements.
- **`fulfillment`** (new) — entitlements, redemptions, due-today, collection recording; quota (N per period).
- **`users`/`accounts`** — guest provisioning + claim; addresses.
- **`tax`** (new) — registrations, Stripe Tax config, **withholding tax from transfers**, filing-partner integration.
- **`notifications`** (new) — email dispatch via jobs + templates.
- **`webhooks`** (new) — signature verification, dedupe, enqueue.
- **`admin`** — approvals, commission overrides, refund/dispute ops, reconciliation views.
- **`reconciliation`** (new) — compare Stripe balances vs local mirrors; detect/repair drift.
- **`reviews`** — Phase 3.

### HTTP routes
- **UI routes** (`views.routes`) — the pages from the UI inventory.
- **API routes** (`modules.routes`) — the **Stripe webhook endpoint** (must receive the **raw body** for signature verification and be **CSRF-exempt**), plus any internal/client API.
- The per-module `routes.clj` files are currently empty/unused — routes stay centralised.

### Middleware / cross-cutting
- **`wrap-subscriber`** guard (account-type `:user`) alongside the existing vendor guard.
- **`wrap-vendor-approved`** gate (approval + capable Connect account before listing/payout).
- **Ownership scoping** helpers (tenant-scoped queries everywhere).
- **Raw-body / CSRF handling** for the webhook route — a real gotcha given the current middleware chain.
- **Rate limiting** (especially the webhook and code-entry endpoints).

### Webhooks & jobs
- **Ingest:** verify signature → record in `provider_events` (idempotent) → enqueue a job → return 200 fast.
- **Events:** `account.updated`, `checkout.session.completed`, `customer.subscription.created/updated/deleted`, `invoice.paid`/`payment_failed`, `charge.refunded`, `charge.dispute.created/closed`, `transfer.*`, `payout.*`.
- **Job handlers:** webhook side effects, receipt/renewal/dunning emails, payout sync, entitlement resets, reconciliation.

### Config & secrets
Add to `system.edn` (via `#env`): Stripe secret/publishable keys, **webhook secret**, mode (test/live), tax settings, **commission default**, credit-threshold default, worker interval/concurrency.

### Email
Extend the existing `auth/mailer` into a notification layer with templates: receipt/invoice, renewal reminder, dunning, cancellation, account-claim magic link, vendor new-subscriber/payout/failed-payment.

### Telemetry
Spans around Stripe calls and webhook processing; metrics + alerts on webhook failures, dunning spikes and payment drift (`shared.telemetry/with-span`, `incr!`).

### Testing
A **fake provider** behind `:payments/provider`; **Stripe test mode + Stripe CLI**; **recorded webhook fixtures**; DB-backed tests in `sub_market_test`; integration tests for checkout → webhook → mirror.

### Phase 1 minimum backend set
Migration 005+ (vendors/accounts/products/prices/subscriptions/payments/provider_events/jobs) → `:payments/provider` component → `:jobs/worker` → webhook ingest → vendor onboarding + Connect → plan/price sync → Checkout Session → subscription/payment mirror → entitlement + redemptions (manual/short code) → subscriber/vendor guard + scoping → receipts + claim email.

### Notable backend decisions to make
1. **Stripe client** — official `stripe-java` via interop vs a thin Clojure wrapper vs direct HTTP + JSON (given the existing jackson pins).
2. **Webhook raw-body** approach with the current middleware chain.
3. **Price ownership** — confirm prices live on the **platform account** (platform-as-MoR), not the connected account.
4. **Job queue** mechanics (polling interval, locking, retries) and the interface that lets an external queue slot in.
5. **Entitlement model** — model "N per period" now (N = 1 for periodic) to avoid a later migration.
6. **PII masking** implementation for the vendor subscriber roster.

---

## Infrastructure inventory

Grounded in what exists today (Docker Compose Postgres, the three CI workflows, Logback, optional OTel, a `/health` module) plus the locked decisions.

### What exists today
- **Local:** `docker-compose.yaml` provides Postgres for dev/test; the app runs via `clojure -M:dev -m app.core dev` (Integrant + Undertow); Tailwind/npm builds `resources/public/output.css`.
- **CI:** `run-tests.yml` (Java 24 + JUnit), `lint.yml` (clj-kondo + cljfmt), `dependency-scan.yml` (weekly clj-watson + npm audit).
- **Observability:** Logback + SLF4J; optional OTel via `shared.telemetry`; `/health` module.
- **Not present:** production deploy target, Dockerfile (`:build` alias is dangling — no `build.clj`), secrets management, email provider, scheduler, or queue.

### Runtime & hosting

| Concern | Notes |
|---|---|
| Hosting target | Greenfield. PaaS (Fly.io / Render / Railway) vs cloud (AWS/GCP/Azure). **EU region likely** (VAT/GDPR). |
| Processes | **Web** (Undertow) + **worker** (job queue) as separate processes; web is stateless (DB-backed sessions) so it scales horizontally |
| Packaging | Uberjar or container image; add a `build.clj`/Dockerfile |
| Reverse proxy / TLS | TLS termination, HTTP→HTTPS, HSTS |
| Domains | App domain; optional vendor storefront subdomains (decide) |

### Database

| Concern | Notes |
|---|---|
| Managed Postgres | Production instance; version parity with dev/test |
| Migrations | Currently run at boot when `:migrate?`; in prod run as a **deploy step**, not on boot |
| Connection pooling | Pool per process; likely **PgBouncer** once web + worker scale |
| Extensions | **`pg_trgm`** for fuzzy search; **PostGIS** (or lat/lng + haversine) for **location/pickup & delivery-area** queries |
| Backups / PITR | Automated backups + point-in-time recovery; restore drills |
| Read replicas | Later, for reporting/reconciliation |

### Background processing & scheduling
- **Postgres job queue + worker process** (locked) — needs concurrency, locking, backoff, dead-letter, and monitoring.
- **Scheduler** for recurring tasks (reconciliation, payout sync, tax-threshold monitoring, cleanup) — choose **`pg_cron`** vs an in-worker scheduler vs an external cron. **Stripe owns billing scheduling**, so no renewal cron.

### Webhooks & ingress
- Public **HTTPS endpoint** reachable by **Stripe** (and the filing partner); stable URL; app-level signature verification.
- **Local dev:** Stripe CLI (`stripe listen`) forwarding.
- **Staging:** a real reachable endpoint — essential, because **Connect onboarding and live webhooks can't be exercised locally**.
- Retries, idempotency, and a dead-letter path for failed events.

### Environments, config & secrets

| Env | Purpose |
|---|---|
| Local | Dev + Stripe CLI + test mode |
| CI/test | Ephemeral Postgres, test mode |
| **Staging** | Webhook + Connect onboarding rehearsal, **separate Stripe account** |
| Production | Live Stripe, live keys |

- **Secrets:** Stripe secret/publishable/webhook keys, DB creds, SMTP creds, filing-partner creds — via host env vars or a **secrets manager** (Doppler / AWS Secrets Manager / Vault). Never in git.
- **Config:** `system.edn` + `#env` per environment.

### Observability
- **Logs:** centralised (PaaS-native / CloudWatch / Loki).
- **Metrics + traces:** OTel agent → collector → backend (Grafana, Datadog, Honeycomb).
- **Error tracking:** Sentry (or equivalent).
- **Uptime + health:** `/health` behind a load-balancer check.
- **Alerts:** webhook failures, **payment drift**, dunning spikes, **queue backlog**.
- **Dashboards:** payments, queue, API latency.

### Email
- **Transactional provider** (Postmark / SES / Resend / SendGrid) behind the existing mailer; **SPF/DKIM/DMARC** on the sending domain; bounce/complaint handling. Powers magic links, receipts, dunning, vendor notifications.

### Networking & security
- TLS everywhere; **WAF / DDoS** (e.g. Cloudflare); app-level **rate limiting**; secret rotation.
- **Data residency** — EU hosting for GDPR; vendor DPA + sub-processor list.
- Backups/DR runbook.

### Payments infrastructure
- Stripe **platform account** + Connect enabled; **Stripe Tax** with platform registrations; **filing-partner** account/integration; Stripe CLI for dev.
- Live vs test keys kept strictly separate per environment.

### Static assets & storage
- `output.css` + `resources/public` served via app or **CDN**, with cache headers and asset fingerprinting.
- Product images are currently **URLs** (no upload). If we add uploads later → **object storage (S3) + CDN**.

### CI/CD
- Existing CI stands; add a **build** (container/uberjar), a **deploy** workflow (staging on merge, prod on approval/tag), a **migration** step, and **post-deploy smoke tests**.
- GitHub Actions environments/secrets; **rollback** strategy.

### Phase 1 minimum infra set
Managed Postgres (+ `pg_trgm`/PostGIS if discovery needs it) → one web + one worker process behind TLS → secrets for Stripe + DB → **public webhook endpoint** + Stripe CLI locally → a **staging environment with a separate Stripe account** → transactional email → error tracking + queue/webhook alerting → a deploy pipeline with a migration step.

### Notable infra decisions to make
1. **Hosting target** (PaaS vs cloud) and **region** (EU?).
2. **Managed Postgres** provider/version + pooling (PgBouncer).
3. **Search/location** — Postgres FTS/`pg_trgm` + PostGIS vs external.
4. **Scheduler** mechanism (`pg_cron` vs in-worker vs external).
5. **Secrets manager** choice.
6. **Observability stack** (logs/metrics/traces/errors).
7. **Email provider** + sending-domain auth.
8. **Environments** — confirm staging with a separate Stripe account.
9. **Image uploads** — stay URL-only, or add S3 + CDN.
10. **Deploy pipeline** shape and rollback approach.

---

## Operations concerns

Several of these are direct consequences of choosing **platform-as-MoR**: the liability we took on becomes operational work.

### Highest priority

| Concern | Why it matters | When |
|---|---|---|
| **Financial reconciliation & drift** | Stripe ↔ local mirror divergence, missed webhooks, wrong transfer/tax amounts. Needs a reconciliation job + alerting from day one. | Phase 1 |
| **Tax operations** | As MoR we must **register, file and remit** continuously; a missed deadline is a penalty. Filing-partner liaison, threshold monitoring, remittance tracking. | Phase 0/1 |
| **Refunds & chargebacks land on us** | Platform balance is debited; we adjudicate "didn't receive" claims, submit dispute evidence, and decide **vendor recovery**. Needs a refund-authority matrix and SLAs. | Phase 1 |
| **Chargeback-ratio monitoring** | High dispute rates get a Stripe account flagged/terminated. Track and act early. | Phase 1 |
| **Dunning & involuntary churn** | Failed payments, retry schedules, grace periods, comms. Stripe helps, but policy and messaging are ours. | Phase 2 |
| **Vendor lifecycle ops** | Manual **approval queue** is ongoing human work; **suspension/offboarding with active subscribers** (migrate? refund? notify?) must be designed for. KYC remediation and **negative-balance** recovery too. | Phase 1–2 |

### Also worth flagging now
- **Fraud & abuse:** fake cash-to-subscription, fake **redemption** recording, code brute-force, account takeover, promo abuse. Needs audit trail + rate limits + anomaly checks.
- **Two-sided support:** subscribers *and* vendors need help; decide the channel early (helpdesk vs in-app messaging) and the escalation path.
- **Subscription / consumer law:** auto-renewal rules (EU cooling-off, US FTC "negative option", California auto-renewal) require **clear disclosure, easy cancellation, pre-renewal notices** — shapes UI, not just policy.
- **VAT invoicing requirements:** invoices must carry specific fields; the platform is the seller on them.
- **GDPR lifecycle:** DSARs (export/delete) interact awkwardly with **backups and financial records** (legal retention vs erasure) — needs a retention policy.
- **Third-party outage modes:** if Stripe / email / filing partner is down, what degrades (checkout vs everything) and what queues.
- **Reliability & incident response:** on-call, runbooks, severity levels, a **status page**, and **DR drills** (RTO/RPO).
- **Observability thresholds:** define what actually pages someone — webhook failure rate, queue backlog, payment drift, dunning spike.
- **Disintermediation monitoring:** vendors steering customers off-platform; partly an ops-detection problem.
- **Reporting/BI:** vendor statements, MRR/churn, and an **audit trail** for financial/admin actions (`audit_log` exists).

### The through-line
Platform-as-MoR buys vendor-friendly flexibility, but the **ops load shifts onto us**: tax filing/remittance, refund/dispute adjudication, and chargeback risk become operational responsibilities rather than outsourced. That is the trade against the MoR route, and it should be staffed and documented, not just built.

### Decide early
1. **Support tooling** — helpdesk vs in-app messaging, and who owns refunds/disputes.
2. **Refund/dispute + vendor-recovery policy** — the authority matrix and recovery rules; they touch code, finance and support simultaneously.

---

## Open items (lighter — refine, not blocking)

- Refund / dispute handling UX.
- Payout schedule and currency.
- **When** Stripe Connect onboarding is triggered in the vendor journey (signup vs first listing vs first sale).
- Contact **masking vs proxying** for vendors (anti-disintermediation).
- Notification channels (email-first? add SMS/push later?).
- Admin/ops depth.
- Exact discovery/filter dimensions.
- GDPR specifics (retention, export/delete flows).
- Marketing-copy rewrite to match the commission model.

---

## Current codebase context (starting point)

- **Built:** auth (accounts/users/vendors split, sessions, verification), vendor **product CRUD** (`/vendor/products`), landing page, dashboard shells.
- **Schema present:** `accounts`, `users`, `vendors`, `sessions`, `verification_tokens`, `products`, `product_billing_intervals`, `subscriptions`, `payments`, `audit_log`.
- **Stubs:** `modules.subscriptions`, `modules.billing`, `modules.reviews`, `modules.admin`, `modules.marketplace`, `modules.users`, `modules.vendors`, and `components.stripe`.
- **Note:** `payments.stripe_payment_id` + `components/stripe` were placeholder Stripe assumptions. The provider choice is now confirmed as Stripe — but Stripe is **not** an MoR, so the platform acts as merchant of record (see decisions 7–9). `components.stripe` becomes the real integration point.
