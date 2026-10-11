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
