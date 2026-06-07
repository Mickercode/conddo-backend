# Studio Dev Onboarding — Rebuilding Seb&Bayor's Website on Conddo

**Welcome.** This is the only doc you need to rebuild
`sebandbayor.com.ng` on top of the Conddo API. Read once, then keep
it open in a tab. Every API endpoint your site needs is listed here
with a ready-to-run `curl` example.

**Tenant**: Seb & Bayor Pharmaceutical
**Tenant slug**: `seb-bayorpharmaceutical`
**API host**: `https://api.conddo.io` (production) — Render maps this
to `conddo-backend.onrender.com`
**Vertical**: pharmacy
**Plan**: growth (gives you orders, bookings, prescription review, the
9stacks .com.ng domain, business email)

---

## 0. Before you start — what you'll get from ops

Before writing code, you need three things. Ops (David / product
lead) hands these over once Phase 1 of the
[Site Registration Admin](./SITE_REGISTRATION_ADMIN_SPEC.md) flow runs:

| Thing | Format | Where to put it |
|---|---|---|
| **Site API Key** | `sk_live_<32 chars>` | `NEXT_PUBLIC_CONDDO_SITE_KEY` env var in your `.env.local` and your Vercel/9stacks deployment |
| **Tenant slug** | `seb-bayorpharmaceutical` | Hard-code or `NEXT_PUBLIC_CONDDO_TENANT_SLUG` |
| **API base URL** | `https://api.conddo.io` | `NEXT_PUBLIC_CONDDO_API_URL` |

⚠ **The API key is shown to you exactly once when ops issues it.** Save
it immediately. If you lose it, ops has to rotate (which breaks the
old key) and re-share. Never commit it to git. Never paste it in
Slack DM or email — use 1Password or a similar secret-sharing tool.

> When you receive the key, immediately set it in:
> - Local: `.env.local` with `NEXT_PUBLIC_CONDDO_SITE_KEY=sk_live_...`
> - Vercel: project Settings → Environment Variables → add for
>   Production + Preview, encrypted
> - Git: add `.env.local` to `.gitignore` if it's not already
>
> Use the key as a Bearer-style header on every request:
> `X-Conddo-Site-Key: ${NEXT_PUBLIC_CONDDO_SITE_KEY}`

---

## 1. The 13 endpoints, mapped to your website

Every endpoint is `https://api.conddo.io/api/v1/public/seb-bayorpharmaceutical/<path>`.

| # | Section | Method + Path (suffix) | Where it's called from |
|---|---|---|---|
| 1 | §1 Store info | `GET /store-info` | Header, footer, "About us", every page header (cache it!) |
| 2 | §3 Products | `GET /pharmacy/products?category=&q=&page=&limit=` | Catalog page, search results, category browse |
| 3 | §3 Product detail | `GET /pharmacy/products/{productSlug}` | Individual product page |
| 4 | §3 Categories | `GET /pharmacy/categories` | Sidebar filter, nav menu |
| 5 | §10 Upload | `POST /upload` (multipart) | Prescription image upload widget |
| 6 | §6 Submit Rx | `POST /pharmacy/prescriptions` (customer JWT) | After upload step, attach the file URL |
| 7 | §11 Delivery fee | `GET /pharmacy/delivery-fee?state=Lagos` | Cart / checkout — show delivery cost |
| 8 | §4 Cart (read) | `GET /pharmacy/cart` (customer JWT) | Cart icon badge, cart drawer |
| 9 | §4 Cart (write) | `POST /pharmacy/cart` (customer JWT) | "Add to cart" button |
| 10 | §4 Cart (clear) | `DELETE /pharmacy/cart` (customer JWT) | After successful order |
| 11 | §5 Place order | `POST /pharmacy/orders` (customer JWT) | Checkout submit button |
| 12 | §5 Order history | `GET /pharmacy/orders` (customer JWT) | "My orders" page |
| 13 | §5 Single order | `GET /pharmacy/orders/{orderId}` (customer JWT) | Order detail page (post-checkout) |

Plus the customer auth endpoints (§2):
- `POST /auth/register` — sign-up form
- `POST /auth/login` — login form
- `GET /auth/me` (customer JWT) — "am I logged in?" check on app boot
- `POST /auth/forgot-password` + `POST /auth/reset-password` — password reset flow

Auth model recap:
- **Every request** carries `X-Conddo-Site-Key`. This is the site's
  identity — your "I am Seb&Bayor's website" credential.
- **Logged-in customer requests** ALSO carry `Authorization: Bearer
  <customer-jwt>`. This is the customer's identity. Issued on
  register/login, valid until expiry.

---

## 2. Copy-paste curl integration test plan

Run through these in order. Each test verifies one piece of the
integration. **Set environment variables once**, then every command
just works:

```bash
export SITE_KEY="sk_live_REPLACE_WITH_OPS_KEY"
export API="https://api.conddo.io/api/v1/public/seb-bayorpharmaceutical"
```

### Test 1 — Store info (proves the key works at all)

```bash
curl -sS "$API/store-info" \
  -H "X-Conddo-Site-Key: $SITE_KEY" | head -c 400
```

**Expect**: `{"store": {"name": "Seb & Bayor Pharmaceutical", "slug": "seb-bayorpharmaceutical", ...}}`

If you get **401 UNAUTHENTICATED**: your key is wrong or hasn't been
activated by ops. Ping them.

If you get **404 SITE_NOT_LIVE**: ops registered the site but hasn't
flipped `is_active=true` yet. Ping them.

### Test 2 — Product catalog

```bash
curl -sS "$API/pharmacy/products?limit=5" \
  -H "X-Conddo-Site-Key: $SITE_KEY"
```

**Expect**: `{"products": [...], "pagination": {"page": 1, "limit": 5, "total": ..., "pages": ...}}`

If `products` is empty, the pharmacist hasn't seeded their inventory
yet — ping product to make sure Oluwaseun's loaded products via the
conddo-app dashboard.

### Test 3 — Categories

```bash
curl -sS "$API/pharmacy/categories" \
  -H "X-Conddo-Site-Key: $SITE_KEY"
```

**Expect**: `{"categories": [{"name": "Prescription Drugs", "slug": "prescription", "productCount": ...}, ...]}`

### Test 4 — Delivery fee lookup

```bash
curl -sS "$API/pharmacy/delivery-fee?state=Lagos" \
  -H "X-Conddo-Site-Key: $SITE_KEY"
```

**Expect**: `{"state": "Lagos", "fee": 1500, "estimate": "2-4 hours (same day)", ...}`

### Test 5 — Customer auth — register

```bash
curl -sS -X POST "$API/auth/register" \
  -H "X-Conddo-Site-Key: $SITE_KEY" \
  -H "Content-Type: application/json" \
  -d '{
    "fullName": "Test Customer",
    "email": "test+'$(date +%s)'@example.com",
    "phone": "08099999999",
    "password": "TestPass123!"
  }'
```

**Expect**: `201` with `{"success": true, "token": "...", "customer": {...}}`. Save the token:

```bash
export CUSTOMER_TOKEN="<token-from-response>"
```

### Test 6 — Customer auth — login (separate from register)

```bash
curl -sS -X POST "$API/auth/login" \
  -H "X-Conddo-Site-Key: $SITE_KEY" \
  -H "Content-Type: application/json" \
  -d '{
    "email": "<the-email-you-just-registered>",
    "password": "TestPass123!"
  }'
```

**Expect**: `200` with `{"success": true, "token": "...", "customer": {...}}`

### Test 7 — /auth/me round-trip

```bash
curl -sS "$API/auth/me" \
  -H "X-Conddo-Site-Key: $SITE_KEY" \
  -H "Authorization: Bearer $CUSTOMER_TOKEN"
```

**Expect**: `{"customer": {"id": "...", "fullName": "Test Customer", ...}}`

### Test 8 — Upload a file

```bash
# Use any small image you have locally
curl -sS -X POST "$API/upload" \
  -H "X-Conddo-Site-Key: $SITE_KEY" \
  -H "Authorization: Bearer $CUSTOMER_TOKEN" \
  -F "file=@/path/to/test-rx-image.jpg"
```

**Expect**: `{"url": "https://cdn.conddo.io/..."}`. Save the URL:

```bash
export RX_URL="<url-from-response>"
```

### Test 9 — Submit a prescription

```bash
curl -sS -X POST "$API/pharmacy/prescriptions" \
  -H "X-Conddo-Site-Key: $SITE_KEY" \
  -H "Authorization: Bearer $CUSTOMER_TOKEN" \
  -H "Content-Type: application/json" \
  -d "{
    \"fileUrl\": \"$RX_URL\",
    \"patientName\": \"Test Customer\",
    \"prescriberName\": \"Dr. Test\",
    \"notes\": \"Smoke test prescription\"
  }"
```

**Expect**: `201` with `{"success": true, "prescription": {"id": "...", "status": "PENDING", ...}}`.

**Verify on the dashboard side**: the pharmacist (Oluwaseun) should
see this prescription appear in their conddo-app dashboard at
`/prescriptions/review` with status "Awaiting review".

### Test 10 — Add a product to cart

```bash
# First grab a product id from Test 2's response
export PRODUCT_ID="<some-product-uuid>"

curl -sS -X POST "$API/pharmacy/cart" \
  -H "X-Conddo-Site-Key: $SITE_KEY" \
  -H "Authorization: Bearer $CUSTOMER_TOKEN" \
  -H "Content-Type: application/json" \
  -d "{\"productId\": \"$PRODUCT_ID\", \"quantity\": 2}"
```

**Expect**: `{"cart": {"items": [{"productId": "...", "quantity": 2, ...}], "subtotal": ..., "itemCount": 2}}`

### Test 11 — Place an order

```bash
# Add a delivery address first (Test 11a) — or for the smoke test,
# the order endpoint will validate but you can provide a phony addressId.

curl -sS -X POST "$API/pharmacy/orders" \
  -H "X-Conddo-Site-Key: $SITE_KEY" \
  -H "Authorization: Bearer $CUSTOMER_TOKEN" \
  -H "Content-Type: application/json" \
  -d "{
    \"items\": [{\"productId\": \"$PRODUCT_ID\", \"quantity\": 2}],
    \"addressId\": \"<the-address-id-from-Test-11a>\",
    \"notes\": \"Smoke test order\"
  }"
```

**Expect** (if the product doesn't require a prescription): `201` with
`{"success": true, "order": {"id": "...", "status": "PENDING", "paymentStatus": "PENDING", "paymentLink": "https://pay.routepay.com/...", "subtotal": ..., "deliveryFee": 1500, "total": ...}}`.

**If the product requires a prescription** AND you didn't link one:
`400 PRESCRIPTION_REQUIRED`. Re-run with `"prescriptionId": "<id-from-Test-9>"`.

**Verify on the dashboard**: the pharmacist sees this order at
`/orders` with status "PENDING".

### Test 12 — Open the payment link

Visit the `paymentLink` URL from Test 11 in a browser. RoutePay's
hosted checkout opens. Use their test card details (ops will share).
Pay. The order's `paymentStatus` flips to `PAID`.

### Test 13 — Check order history

```bash
curl -sS "$API/pharmacy/orders" \
  -H "X-Conddo-Site-Key: $SITE_KEY" \
  -H "Authorization: Bearer $CUSTOMER_TOKEN"
```

**Expect**: `{"orders": [{"id": "...", "status": "PENDING" | "PROCESSING", "paymentStatus": "PAID", ...}]}`

---

## 3. Wire-up checklist for the website code

In your Next.js (or whatever framework) project:

### 3.1 API client (`lib/conddo.ts`)

```typescript
const API = process.env.NEXT_PUBLIC_CONDDO_API_URL ?? "https://api.conddo.io";
const SITE_KEY = process.env.NEXT_PUBLIC_CONDDO_SITE_KEY!;
const SLUG = process.env.NEXT_PUBLIC_CONDDO_TENANT_SLUG ?? "seb-bayorpharmaceutical";

type Opts = {
  method?: string;
  body?: unknown;
  customerToken?: string;     // pass when the call requires a logged-in customer
};

export class ConddoApiError extends Error {
  constructor(public code: string, message: string, public status: number) {
    super(message);
  }
}

export async function conddo<T>(path: string, opts: Opts = {}): Promise<T> {
  const res = await fetch(`${API}/api/v1/public/${SLUG}${path}`, {
    method: opts.method ?? "GET",
    headers: {
      "X-Conddo-Site-Key": SITE_KEY,
      "Content-Type": "application/json",
      ...(opts.customerToken ? { Authorization: `Bearer ${opts.customerToken}` } : {}),
    },
    body: opts.body ? JSON.stringify(opts.body) : undefined,
  });
  const json = await res.json();
  if (!res.ok || json.success === false) {
    throw new ConddoApiError(
      json.error?.code ?? json.error ?? "REQUEST_FAILED",
      json.error?.message ?? json.message ?? res.statusText,
      res.status,
    );
  }
  return json as T;
}
```

### 3.2 Replace every existing `app/api/*` route handler

Whatever Seb&Bayor's current backend does for products / orders /
prescriptions, the website now calls Conddo instead. Example for the
catalog page:

```typescript
// app/products/page.tsx (server component)
import { conddo } from "@/lib/conddo";

type ProductsResponse = {
  products: { id: string; nameGeneric: string; nameBrand: string;
              price: number; images: string[]; slug: string }[];
  pagination: { total: number; pages: number };
};

export default async function ProductsPage() {
  const { products } = await conddo<ProductsResponse>("/pharmacy/products");
  return (
    <ul>
      {products.map(p => (
        <li key={p.id}>{p.nameGeneric} — ₦{p.price.toLocaleString()}</li>
      ))}
    </ul>
  );
}
```

For the cart and authenticated routes, you'll need to thread the
customer JWT through. Standard pattern: store the customer token in
an httpOnly cookie on `/auth/login` response, read it from the cookie
in server components.

### 3.3 Customer session

```typescript
// app/auth/login/route.ts (route handler)
import { conddo } from "@/lib/conddo";
import { cookies } from "next/headers";

export async function POST(req: Request) {
  const body = await req.json();
  const result = await conddo<{ token: string; customer: any }>("/auth/login", {
    method: "POST",
    body,
  });
  cookies().set("conddo_customer_token", result.token, {
    httpOnly: true,
    secure: true,
    sameSite: "lax",
    maxAge: 60 * 60 * 24 * 7,  // 7 days
  });
  return Response.json({ success: true, customer: result.customer });
}
```

For every authenticated server call, pull the cookie:

```typescript
const token = cookies().get("conddo_customer_token")?.value;
const { cart } = await conddo<{ cart: Cart }>("/pharmacy/cart", { customerToken: token });
```

### 3.4 Don't store product data locally

The pharmacist updates inventory in Conddo's dashboard, not yours.
Always fetch fresh — your website is the **face**, Conddo is the
**brain** (see WEBSITE_INTEGRATION_SPEC.md §"The Architecture in
Plain Terms"). Cache with a short TTL (30-60s) on product lists if
you need to. Single-product pages: cache 5-15s.

### 3.5 Real-time updates (optional, Phase 2)

The spec mentions a WebSocket at `wss://api.conddo.io/ws/public/seb-bayorpharmaceutical`
for push updates on stock + availability + order status. Not required for
launch — polling every 30s on the cart / order tracking pages is fine.

---

## 4. Error codes to handle

Wherever you call Conddo, design for these:

| Code | Meaning | Where it fires | What to show the customer |
|---|---|---|---|
| `UNAUTHENTICATED` | Invalid Site API Key | Every endpoint | Generic "Something went wrong" — site is misconfigured, page support |
| `SITE_NOT_LIVE` | Site marked inactive or not QA-approved | Every endpoint | "We're updating our site, back shortly" |
| `STOCK_SHORTAGE` | Cart item stock disappeared between cart-add and order-place | `POST /pharmacy/orders` | "Item is no longer in stock — your cart was updated" + remove the item |
| `PRESCRIPTION_REQUIRED` | Order contains Rx item without a linked approved prescription | `POST /pharmacy/orders` | Redirect to /upload-prescription flow |
| `OUT_OF_STOCK` | Same as STOCK_SHORTAGE for older response format | Same | Same handling |
| `NOT_FOUND` | Product slug doesn't exist, order doesn't belong to you, etc. | Various | Generic 404 page |
| `FORBIDDEN` | Customer trying to read someone else's order | `GET /pharmacy/orders/{id}` | 403 — should never happen on your side if you only show the logged-in customer's own orders |
| `REQUEST_FAILED` | Anything else | Various | Generic toast, retry button |

---

## 5. Pre-launch checklist

Before pushing the rebuilt site live, walk this list:

- [ ] All 13 curl tests above pass
- [ ] Cart persists across browser refresh (cookie / token round-trip works)
- [ ] Logged-in customer can place an order that lands on the
      pharmacist dashboard at `/orders`
- [ ] Customer can upload a prescription that appears at
      `/prescriptions/review` on the pharmacist dashboard
- [ ] Routepay test card flow completes; order's `paymentStatus`
      reflects PAID in both the customer-facing order page AND the
      pharmacist dashboard
- [ ] Mobile responsive — open on a real Android phone (most
      Nigerian traffic) at 360px + 412px
- [ ] All product detail pages render even when product has no
      images (fall back to a placeholder)
- [ ] Search returns correct results (`?q=ibuprofen`)
- [ ] Category filter works (`?category=prescription`)
- [ ] Delivery fee shows correctly for every Nigerian state customer
      might enter (Lagos, Abuja, plus a few outliers)
- [ ] Footer "About us" pulls from `store-info` (no hardcoded copy)
- [ ] Opening hours render correctly per the `store-info` response
- [ ] WhatsApp link uses the number from `store-info.whatsapp`
- [ ] Social media icons link to URLs from `store-info.socials`
- [ ] Network panel in DevTools: every request has both the
      `X-Conddo-Site-Key` header and (where applicable) the
      Authorization Bearer header
- [ ] No API responses contain the Site API Key (it's never sent
      back — if it ever appears in a response, that's a BE bug)
- [ ] `.env.local` is in `.gitignore`; production secrets are in
      Vercel / 9stacks dashboard only
- [ ] Site builds without warnings (`npm run build`)
- [ ] Lighthouse score: Performance ≥ 75, SEO ≥ 90

When all boxes are checked: submit the deployed URL on Studio's job
detail page → "Submit for QA". A reviewer will run through this
checklist on the live site and approve.

---

## 6. Who to ask when stuck

| Issue | Who | Reach via |
|---|---|---|
| 401 / key issues | Ops (David) | Internal board |
| 500 / endpoint not behaving as spec says | BE team | Internal board |
| Spec ambiguity | Product lead | Internal board |
| Routepay payment doesn't complete | Payments team (or product lead) | Internal board |
| MinIO / Cloudinary upload fails | DevOps (or BE team) | Internal board |
| Need a test prescription image | Ops will provide a sample | — |

---

## 7. After go-live

Once Seb&Bayor is live:

- Tenant Site API Key is yours to safeguard. If you ever suspect it's
  leaked → ping ops to rotate. Old key invalidates immediately; you
  get a new plaintext, redeploy.
- Site updates on the **brand side** (logo, hours, product images,
  etc.) happen in the conddo-app dashboard — no code redeploy needed.
- Site updates on the **layout / UX** side (new sections, design
  iterations) happen in your codebase. Submit each one via Studio's
  job flow for QA review.
- Real-time orders + prescriptions flow into Oluwaseun's dashboard
  the moment a customer submits — already working.

Welcome aboard. Build well.

---

*Reference specs: [PHARMACY_PUBLIC_API_SPEC.md](./PHARMACY_PUBLIC_API_SPEC.md)
(every endpoint shape) · [WEBSITE_INTEGRATION_SPEC.md](./WEBSITE_INTEGRATION_SPEC.md)
(the public-API architecture) · [SITE_REGISTRATION_ADMIN_SPEC.md](./SITE_REGISTRATION_ADMIN_SPEC.md)
(how ops provisioned your Site API Key).*
