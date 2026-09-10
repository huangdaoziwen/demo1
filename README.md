# WeBox

WeBox is a responsive employee meal-ordering platform with a Spring Boot API, transactional inventory, and a mobile-first web client. All user-facing copy and seeded content is English.

## Stack and rationale

- **Java 17 + Spring Boot 3 / JPA:** conventional, maintainable service boundaries and database transactions.
- **MySQL 8.4:** independently deployed durable storage. Money uses `DECIMAL`/`BigDecimal`, never floating point on the server.
- **Dependency-free SPA:** fast first load and a small operational surface. Responsive CSS supports desktop and phone screens, incremental menu rendering, and throttled cart actions.
- **Pessimistic inventory locks + idempotency keys:** serializes stock changes and makes retried submissions safe.
- **SSE + Caffeine:** pushes stock changes to every open menu and absorbs repeated reads of the date-scoped menu, with invalidation after every stock or catalogue mutation.

## Run locally

Requirements: Docker, JDK 17, and Maven 3.9+.

```bash
docker compose up -d mysql
mvn spring-boot:run
```

Open <http://localhost:8080>. The schema and today's/tomorrow's menu are initialized automatically. A Console-ready administrator is seeded as `admin@webox.com` / `Admin123`; create an employee from the checkout sign-in flow. Configuration can be overridden with `DB_URL`, `DB_USER`, and `DB_PASSWORD`.

```bash
mvn test
mvn package
```

## Architecture

```text
Browser SPA  ── JSON/HTTPS ──>  Spring MVC
                                   │
                           validation + auth token
                                   │
                           transactional JPA services
                                   │
                             MySQL 8 (external)
```

The menu is read from date-scoped inventory. Checkout validates quantity and duplicate meal rules, resolves cutoff times, locks each inventory row, decrements stock, and writes the order in one transaction. Cancellation restores stock in one transaction. The database uniqueness constraints are the final defense for email, menu, and active-meal invariants.

## API reference

All request/response bodies are JSON. Authenticated routes use `Authorization: Bearer <token>`.

| Method | Path | Purpose |
|---|---|---|
| POST | `/api/auth/register` | Register with `{email,password}` and return `{token,role,email}` |
| POST | `/api/auth/login` | Authenticate and return a session |
| GET | `/api/menu?date=YYYY-MM-DD&q=` | Today's available dishes and live inventory |
| POST | `/api/orders` | Idempotently place an order; requires `idempotencyKey`, `date`, `slot`, `address`, and `items` |
| GET | `/api/orders` | Current employee's order history |
| POST | `/api/orders/{id}/cancel` | Cancel a pending owned order and restore inventory |
| GET | `/api/inventory/stream` | SSE stream of `stock_update` events |
| POST | `/api/assistant/recommend` | Stream safe recommendations filtered by preferences, inventory, allergens, and seven-day history |
| GET | `/api/admin/metrics` | Admin-only dashboard aggregates for today and the last seven days |
| POST | `/api/admin/uploads` | Admin-only image upload (`multipart/form-data`, 5 MB maximum) |

Example order item: `{"dishId":1,"quantity":2,"selections":"Whole Wheat · Mustard","optionPrice":0}`. Errors use an HTTP status appropriate to the failure and an English `message` field.

## Security and operations

Passwords are BCrypt hashes. Inputs are constrained and validated; JPA parameter binding prevents SQL injection. Inventory locking prevents overselling, an order idempotency key handles client retries, and admin routes enforce role authorization. Uploaded images are stored in `UPLOAD_DIR` (default `./uploads`); production deployments should mount durable storage or replace it with object storage. In production, terminate TLS at the ingress, place tokens in secure HTTP-only cookies, rotate secrets, and add database migrations and operational metrics.

## AI conversation record

The raw conversation export for this implementation belongs in [`ai-conversations/`](ai-conversations/). The host IDE must export the current native conversation there; the application does not fabricate or summarize this required audit artifact.
