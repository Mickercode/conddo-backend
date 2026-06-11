# FE Handoff — POS Phase 1 (Cash + Transfer MVP)

Owner: BE (Claude) → FE
Status: Contract frozen. BE is implementing now; FE can wire screens
in parallel against the shapes below. Expect ship within 24h.

Scope: a usable in-store point-of-sale for pharmacy verticals. Cashier
opens a shift, runs sales (cart → payment → receipt), closes the
shift with a cash count. Card-in-store and discounts come in Phase 2;
prescription gating + Rx-only checks come in Phase 3.

---

## 0. Module gating

POS is gated by feature flag `pos`. Same pattern as the Beta wave —
`@featureFlagGuard.requiresFlag('pos')` on every controller, FE checks
`GET /api/v1/feature-flags` on mount to decide whether to show the POS
nav entry. Refusal envelope is the same `FEATURE_NOT_ENABLED` shape
you already handle.

Roles: `STAFF` and `TENANT_ADMIN` can run sales and shifts. Only
`TENANT_ADMIN` can void a completed sale or force-close someone else's
session.

---

## 1. Domain model

```
PosSession ──< PosSale ──< PosSaleItem
                  │
                  └──< PosPayment
```

- **Session** — a cashier's shift. Exactly one OPEN session per
  `(tenant, cashier)` at a time. Opens with a cash float, closes
  with a counted cash amount; the variance is logged.
- **Sale** — one customer transaction. Lifecycle: `OPEN` → `COMPLETED`
  or `VOIDED`. `OPEN` is the cart-building phase; nothing leaves
  inventory until `COMPLETED`. Soft-attached to a `Customer` for
  loyalty.
- **SaleItem** — one cart line. Snapshots `unitPrice` at add-time so
  later price edits to the Product don't retroactively change the
  receipt.
- **Payment** — one tender. Splits are first-class: a sale can carry
  multiple payments (e.g. ₦3,000 cash + ₦2,500 transfer). Methods in
  v1: `CASH`, `TRANSFER`. Card comes in Phase 2.

All money is **NGN** as `BigDecimal` (FE will see JSON numbers like
`1500.00`). No kobo math in the POS surface — kobo is only inside the
Paystack/Squad gateway boundary.

---

## 2. Endpoints — sessions

### `POST /api/v1/pos/sessions`
Open a shift. The acting user (from JWT) is the cashier.

```json
// request
{ "openingFloat": 5000.00, "notes": "Morning shift" }

// 201 response
{
  "data": {
    "id": "uuid",
    "cashierId": "uuid",
    "status": "OPEN",
    "openingFloat": 5000.00,
    "openedAt": "2026-06-11T08:00:00Z",
    "closedAt": null,
    "notes": "Morning shift"
  }
}
```

Errors: `409 SESSION_ALREADY_OPEN` if the cashier already has an OPEN
session — FE should redirect to that session instead of showing the
open-shift modal.

### `GET /api/v1/pos/sessions/current`
The acting cashier's currently OPEN session, or `null`. FE calls this
on POS app boot; if null, show the open-shift screen.

```json
{ "data": null }
// OR
{ "data": { ...session fields..., "summary": { "salesCount": 12, "totalSales": 47800.00, "totalCash": 21000.00, "totalTransfer": 26800.00 } } }
```

### `GET /api/v1/pos/sessions/{id}`
Full detail incl. summary (same shape as `current`).

### `POST /api/v1/pos/sessions/{id}/close`
Close the shift. The expected cash = `openingFloat + totalCash`. FE
passes the physically counted cash; BE computes the variance.

```json
// request
{ "countedCash": 26050.00, "notes": "₦50 over" }

// 200 response
{
  "data": {
    "id": "uuid",
    "status": "CLOSED",
    "openingFloat": 5000.00,
    "expectedCash": 26000.00,
    "countedCash": 26050.00,
    "cashVariance": 50.00,
    "openedAt": "...",
    "closedAt": "...",
    "summary": { "salesCount": 12, "totalSales": 47800.00, "totalCash": 21000.00, "totalTransfer": 26800.00 }
  }
}
```

Errors: `409 SESSION_HAS_OPEN_SALES` if any sale in this session is
still `OPEN` — FE should prompt to either complete or void them first.

---

## 3. Endpoints — sales

### `POST /api/v1/pos/sales`
Start a new sale. Must have an OPEN session. Returns an empty cart.

```json
// request — both optional
{ "customerId": "uuid-or-null" }

// 201 response — full sale shape (see below)
```

### `GET /api/v1/pos/sales/{id}`
Full sale detail (always returns the same shape — used by FE to
re-render after every mutation):

```json
{
  "data": {
    "id": "uuid",
    "saleNumber": "S-2026-06-11-0042",
    "sessionId": "uuid",
    "cashierId": "uuid",
    "customer": { "id": "uuid", "name": "Mrs Adebayo", "phone": "+234..." } | null,
    "status": "OPEN",
    "items": [
      {
        "id": "uuid",
        "productId": "uuid",
        "productName": "Paracetamol 500mg",
        "sku": "PARA-500",
        "qty": 2,
        "unitPrice": 150.00,
        "lineTotal": 300.00
      }
    ],
    "payments": [
      { "id": "uuid", "method": "CASH", "amount": 300.00, "reference": null, "paidAt": "..." }
    ],
    "subtotal": 300.00,
    "total": 300.00,
    "paid": 300.00,
    "balance": 0.00,
    "openedAt": "...",
    "completedAt": null,
    "voidedAt": null
  }
}
```

`balance > 0` → outstanding; `balance < 0` → change due to customer.

### `POST /api/v1/pos/sales/{id}/items`
Add a line. If the product is already in the cart, qty is summed
(no duplicate lines).

```json
// request
{ "productId": "uuid", "qty": 2 }
// response: full sale shape
```

Errors: `409 INSUFFICIENT_STOCK` with `details: [{ "field": "qty", "message": "Only 5 in stock" }]`. FE should toast and not clear the cart.

### `PATCH /api/v1/pos/sales/{id}/items/{itemId}`
Update qty (e.g. ± buttons). `qty: 0` is rejected — use DELETE instead.

```json
// request
{ "qty": 3 }
// response: full sale shape
```

### `DELETE /api/v1/pos/sales/{id}/items/{itemId}`
Remove the line. Returns full sale shape.

### `POST /api/v1/pos/sales/{id}/payments`
Add a tender. Reference is optional for CASH, required for TRANSFER
(the bank-transfer ref the customer texted, or the auto-captured one
from a payment provider in Phase 2).

```json
// request
{ "method": "TRANSFER", "amount": 2500.00, "reference": "TRF-998877" }
// response: full sale shape
```

### `DELETE /api/v1/pos/sales/{id}/payments/{paymentId}`
Remove a tender (cashier hit the wrong button). Only allowed while
sale is `OPEN`.

### `POST /api/v1/pos/sales/{id}/attach-customer`
Attach (or replace) the customer mid-sale. Drives loyalty/cashback at
complete time.

```json
{ "customerId": "uuid" }
```

### `POST /api/v1/pos/sales/{id}/complete`
Finalize. BE validates `paid >= total`, writes one `SALE_POS` stock
movement per line item (auto-decrement), records the receipt, fires
the loyalty cashback rule if a customer is attached, marks the sale
`COMPLETED`.

```json
// request — no body
// 200 response — full sale shape with status=COMPLETED + a receipt block
{
  "data": {
    ...usual sale shape...,
    "receipt": {
      "saleNumber": "S-2026-06-11-0042",
      "tenant": { "name": "ABC Pharmacy", "address": "...", "phone": "..." },
      "lines": [...],
      "subtotal": 300.00,
      "total": 300.00,
      "payments": [...],
      "change": 0.00,
      "loyaltyEarned": 6.00,
      "cashierName": "Tunde",
      "completedAt": "..."
    }
  }
}
```

FE renders the receipt block to thermal printer (WebUSB / Bluetooth)
and/or to a PDF download.

Errors:
- `400 SALE_HAS_NO_ITEMS` — empty cart
- `400 PAYMENT_INSUFFICIENT` — paid < total, with `details: [{ "field": "balance", "message": "150.00 outstanding" }]`

### `POST /api/v1/pos/sales/{id}/void`
Void an `OPEN` sale. No stock movement (nothing left inventory yet).
Returns the voided sale shape.

To void a `COMPLETED` sale: this is a **refund** (Phase 2). Phase 1
does not support voiding completed sales — `409 SALE_ALREADY_COMPLETED`.

---

## 4. Product picker

### `GET /api/v1/pos/products/search?q=para&limit=20`
Fast picker for the cart UI. Search matches against SKU (prefix),
barcode (exact), and name (substring, case-insensitive). Returns
items in stock first.

```json
{
  "data": [
    { "productId": "uuid", "name": "Paracetamol 500mg", "sku": "PARA-500", "price": 150.00, "stock": 47, "lowStock": false }
  ]
}
```

`stock` is the current on-hand count *including* any open sales —
i.e. live, not reserved. Phase 1 doesn't reserve stock on add-to-cart
(simplifies the model; FE handles the `INSUFFICIENT_STOCK` race
gracefully). Phase 4 may add reservation if double-booking proves
common.

---

## 5. Receipt printing

Receipts are a JSON shape, not server-rendered HTML/PDF. FE owns the
rendering — easier to template, supports thermal/web/email all from
one source.

Suggested FE printers in order of priority:
1. **Browser print** (immediate, no hardware needed)
2. **Thermal via WebUSB** (Epson TM-T20, Star TSP100 — standard ESC/POS)
3. **PDF download / email**
4. Bluetooth thermal (Phase 2)

If the print fails, the sale is still completed — receipts can be
reprinted via `GET /api/v1/pos/sales/{id}` (the `receipt` block is
always present on completed sales).

---

## 6. Loyalty integration

If a sale's `customer` is non-null on complete, BE fires the existing
Cashback Loyalty rules (Beta 1) without any extra wiring. FE just
sees `receipt.loyaltyEarned` on the response.

---

## 7. Screens FE should build

| Screen | Endpoint(s) used | Notes |
|---|---|---|
| Open Shift modal | `POST /sessions` | First boot of POS app each day |
| Sale screen (cart + picker + payment) | `GET/POST sales/*` | The main POS view |
| Customer attach modal | reuse Customer picker + `attach-customer` | Optional per sale |
| Payment modal (split UI) | `POST /sales/{id}/payments` | Cash / Transfer tabs |
| Receipt preview + print | response from `/complete` | After every sale |
| End-of-Shift screen | `POST /sessions/{id}/close` | Counted cash entry |
| Sales history (today's sales) | `GET /sessions/current` `summary` | Within the current shift |

---

## 8. What's NOT in Phase 1

- Card terminal (Squad / Paystack Terminal) — Phase 2
- Discounts (line or cart) — Phase 2
- Refunds / returns — Phase 2
- Parked / held sales — Phase 2
- Prescription gating on Rx-only SKUs — Phase 3
- Batch / expiry surfacing in picker — Phase 3
- Multi-terminal / offline mode — Phase 4

If you need any of these sooner, ping me — we can re-slice.

---

## 9. Open questions for FE

1. **Cashier identity:** is "cashier = the logged-in user" sufficient, or do you want a separate "cashier code" entered at shift open (so one device, many physical cashiers)? Phase 1 assumes the former; the model has room for the latter if you want it.
2. **Sale numbering:** the `saleNumber` format is `S-YYYY-MM-DD-NNNN` per-day per-tenant. OK or do you want a continuous counter?
3. **Barcode scan:** the picker's `?q=` will work for barcode strings if you pass them; do you want a separate `?barcode=` param that does an exact match and adds to cart in one shot?

Reply on those and I'll fold them in before merge.
