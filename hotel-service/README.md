# Local Hotel Service: reservation saga

A standalone Java 17 / Spring Boot 4.1.1 / Maven backend on **9090**, paired with `hotel-availability-ai2` on **8080**. It replaces the Python hotel mock when testing payment and reservation sagas. No Docker, AWS account, OpenAI key, payment account, or real card is needed for the backend/UI tests.

**Local simulator:** no money is collected, no email is sent, and no external PMS is updated. Database-backed simulator effects make recovery and duplicate-delivery behavior observable. The server binds to loopback. This sample has no authentication or tenant isolation; do not expose it as a live booking service.

## Which backend should I run?

Use **this Java service for the complete booking/payment flow**. The companion AI2 project also includes `mock/hotel_api.py`, a smaller Python backend for focused retry, timeout, circuit-breaker and idempotency tests. That mock immediately confirms successful reservations and has no payment saga or Email/PMS delivery.

Both default to port **9090**; run one at a time. AI2 runs on **8080** and calls the chosen backend through `HOTEL_API_BASE_URL`. The Python mock remains part of the AI project's automated test suite even when you choose Java for manual testing.

See the [backend comparison and startup choices](../hotel-availability-ai2/README.md#choose-the-backend-first) or the [repository guide](../README.md).

## Start

From the cloned repository root or extracted combined bundle:

```bash
cd hotel-service
mvn clean verify
mvn spring-boot:run
```

Open **http://localhost:9090/** for the checkout simulator. Enter booking details, click **Simulate payment collection and reserve**, then **Check reservation status**. No card fields are displayed. The simulator creates an opaque `pm_...` token; the UI sends it to the backend, outside any model prompt. Keep the same form details for retries; reload only for an intentional new booking.

Stop the Python mock first if it already owns 9090. The Java backend and Python mock are alternatives on that port. To use another port, set `PORT` and point the AI application's `HOTEL_API_BASE_URL` at it.

The local H2 file database lives in `data/`, relative to the service working directory. Restarting preserves reservations, holds, payment records, outbox work and consumer deduplication records. One application process owns the embedded file database. To reset the demo, stop the service and remove its `data/` directory; this deletes all local bookings.

Demo inventory: hotel **h1**, physical rooms **r1** ($120/night) and **r2** ($150/night), USD. A room can have only one overlapping held/confirmed booking. Dates use `[checkIn, checkOut)`; stays are limited to 30 nights. The backend calculates payment amounts from inventory prices and stay length. Sample prices exclude taxes/fees. Change room or dates for a new booking after a successful run; changing the idempotency key does not create extra inventory.

## Architecture and request flow

```text
User -> Spring AI -> createReservation tool
                         |
Payment UI -> pm_token -> backend-only tool context
                         |
                         v
Hotel Service: local transaction
  reservation PENDING_PAYMENT + inventory hold + PAYMENT_REQUESTED outbox
                         |
              durable worker claims event and commits lease
                         |
              payment HTTP outside DB transaction
                         |
        +----------------+-------------------+
        |                |                   |
     SUCCEEDED        DECLINED          unknown / pending
        |                |                   |
 local transaction   CANCELLED          PAYMENT_UNKNOWN
 CONFIRMED +         release hold       retain hold and event
 RESERVATION_CONFIRMED                    reconcile same key
 outbox event
        |
 local durable fan-out transport (AWS substitute)
        +---------------------+
        |                     |
 Email delivery queue    PMS delivery queue
        |                     |
 independent consumer transactions with event-ID inbox deduplication
```

`BookingService` uses Spring's `TransactionTemplate` so the boundaries are explicit and shared by HTTP and worker paths. Reservation creation, inventory hold and payment outbox insert commit atomically. A room row is locked during availability allocation; normal availability searches are advisory, and booking always checks again under the lock.

The worker has a durable lease/owner, retry count, retry schedule and error code. It first queries the provider by the stored payment key, and only submits a charge if no payment exists. Retries always retain that key and original amount/token. A successful provider result is followed by a local transaction that confirms the reservation and inserts `RESERVATION_CONFIRMED`. A definite decline cancels and releases inventory in one transaction. A timeout, malformed provider response or unknown state retains the hold and schedules reconciliation. Workers recover expired leases after a crash or restart.

The sample deliberately retains unresolved holds rather than assuming a timeout means payment failed. It does **not** implement automatic hold expiry, user cancellation, refunds or a manual review UI. Before real use, add a hold-expiry/cancellation policy with persisted void/refund compensation for late payment success, escalation for long-running reconciliation, and provider-specific idempotency retention rules. Worker retries use capped exponential delay, with no destructive retry exhaustion.

The payment simulator shares the local database process for convenience but exposes a separate HTTP API and commits independently. The payment client rejects attempts to run payment HTTP inside an active database transaction. A real provider adapter must verify its responses/webhooks and enforce idempotency durably; an HTTP header alone cannot provide that guarantee.

### Confirmation event delivery and SQS

For local runs, `notification_deliveries` simulates two durable queues. Outbox publication creates one Email delivery and one PMS delivery. Each consumer owns its own transaction and event-ID deduplication. `notification_inbox` rows represent simulated side effects. Real network side effects require recipient/provider idempotency as well; a local inbox cannot make an external email send atomic with a database transaction.

For AWS, the intended replacement is:

```text
CONFIRMED + outbox (same database transaction)
     -> outbox publisher -> SNS topic
                              +-> Email SQS queue -> Email worker
                              +-> PMS SQS queue   -> PMS worker
```

The publisher marks the event delivered only after publish acknowledgement. A crash after publish can cause redelivery, so preserve the original event ID and deduplicate independently in both consumers. Give each queue its own visibility timeout, retries, dead-letter queue and monitoring. One shared SQS queue would make Email and PMS compete for messages. Actual AWS publishing/consuming is not included in this local simulator. See [AWS SNS-to-SQS fan-out](https://docs.aws.amazon.com/sns/latest/dg/sns-sqs-as-subscriber.html) and [transactional outbox guidance](https://docs.aws.amazon.com/prescriptive-guidance/latest/cloud-design-patterns/transactional-outbox.html).

PMS is an optional replication target here. If PMS acceptance is required for a booking to exist, it must become a booking-critical saga step before `CONFIRMED`.

## Postman: complete success flow

Import **postman/hotel-booking.postman_collection.json**. Its `hotelUrl` defaults to `http://localhost:9090`, and `aiUrl` to `http://localhost:8080`.

1. **Set mode SUCCESS** (or run the following request).

   ```http
   POST http://localhost:9090/simulator/mode
   Content-Type: application/json

   {"mode":"SUCCESS"}
   ```

2. **Collect simulated payment method**:

   ```http
   POST http://localhost:9090/simulator/payment-methods
   ```

   The collection saves `paymentMethodToken` and generates an `idempotencyKey` for this booking. This endpoint simulates provider-hosted collection; never send real card details to it. Manual users copy its returned `pm_...` token.

3. **Create reservation**:

   ```http
   POST http://localhost:9090/reservations
   Content-Type: application/json
   Idempotency-Key: booking-saga-0001
   ```

   ```json
   {
     "hotelId":"h1",
     "roomId":"r1",
     "checkIn":"2027-01-01",
     "checkOut":"2027-01-02",
     "guests":2,
     "guestName":"Alex Guest",
     "guestEmail":"alex@example.com",
     "paymentMethodToken":"COPY_THE_RETURNED_TOKEN"
   }
   ```

   Expect **202** with `{"reservationId":"...","status":"PENDING_PAYMENT"}`. This means accepted for processing, not confirmed or charged yet. The Postman collection saves `reservationId` automatically.

4. **Get reservation status**, after a few seconds:

   ```http
   GET http://localhost:9090/reservations/{{reservationId}}
   ```

   Expect `CONFIRMED` in success mode. A GET always returns current state; it does not charge or create another reservation.

5. **Simulator summary**:

   ```http
   GET http://localhost:9090/simulator/summary
   ```

   On a fresh database, one successful booking eventually gives `reservations: 1`, `successfulPayments: 1`, `heldReservations: 1`, `notifications: 2`. A confirmed reservation retains allocated inventory. The summary also shows outbox attempts, state and error codes without guest details or tokens.

6. Replay the **same POST body, token and Idempotency-Key**. It returns the existing reservation's latest state and does not charge again. Reusing that key with changed booking details or a replacement token returns **409 IDEMPOTENCY_CONFLICT**. A new key represents a new business operation and remains subject to inventory availability.

### Failure and recovery scenarios

Set the mode before creating the reservation. Use a fresh room/date interval or reset the stopped local database between scenarios.

| Simulator mode | Expected observation | Recovery |
|---|---|---|
| `SUCCESS` | Pending -> confirmed; one payment; two notifications | None |
| `DECLINE` | Pending -> cancelled; inventory released; no confirmation notifications | Start a new booking only after addressing payment failure |
| `COMMIT_THEN_TIMEOUT` | Provider stores success but first response times out; transient payment unknown -> confirmed | Worker queries existing payment; one charge only |
| `TIMEOUT_BEFORE_COMMIT` | Payment unknown; held inventory; no stored payment | Change mode to SUCCESS; next worker retry resolves using the same key |
| `PENDING` | Provider pending; reservation payment unknown; hold retained | Resolve provider payment with endpoint below |

To see the intermediate unknown state more easily, start with `SAGA_RETRY_MS=10000 mvn spring-boot:run`. With default timings, the state may reconcile before a manual GET.

For `PENDING`, the payment key is server-generated as `pay_` plus the reservation UUID:

```http
POST http://localhost:9090/simulator/payments/pay_{{reservationId}}/resolve
Content-Type: application/json

{"status":"SUCCEEDED"}
```

Use `DECLINED` to test reconciliation to cancellation. Only a pending simulated payment can be resolved this way.

Missing payment token returns **402 PAYMENT_REQUIRED** before creating a reservation. An invalid/missing idempotency key returns **400**. Missing room returns **404**. Overlapping inventory returns **409 INVENTORY_UNAVAILABLE**. A terminal provider decline is observed through reservation status, rather than as a synchronous HTTP error from creation.

To verify restart recovery, start a `PENDING` scenario, stop/restart the service from the same directory, resolve the pending payment, and GET the same reservation. Do not delete `data/` or generate a new booking key. The persisted event continues; it does not require another tool call.

## Connect Spring AI on 8080

In a second terminal:

```bash
cd hotel-availability-ai2
export OPENAI_API_KEY='your-valid-key'
export HOTEL_API_BASE_URL='http://localhost:9090'
mvn spring-boot:run
```

The key is needed only for `/api/chat`. The direct backend endpoints, checkout simulator and automated tests do not call OpenAI. See the AI project's README for direct API startup without a live model.

Payment collection should happen through the UI/provider, outside chat. After collecting a simulated token, send it in a backend-context header:

```http
POST http://localhost:8080/api/chat
Content-Type: application/json
Idempotency-Key: booking-saga-chat-0001
Payment-Method-Token: pm_COPY_THE_COLLECTED_TOKEN

{"message":"Book hotel h1 room r1 from 2027-01-01 to 2027-01-02 for 2 guests, guest Alex Guest, alex@example.com."}
```

The tool schema contains the seven booking fields, not the payment token or idempotency key. Both are supplied by the server context. The backend POST includes the token; tool responses and status responses exclude it. The UI must preserve the same token, key and details for a retry. The model should explain `PAYMENT_REQUIRED` and direct the guest to checkout, rather than ask for payment credentials.

To check later, no write key is needed:

```http
POST http://localhost:8080/api/chat
Content-Type: application/json

{"message":"Check the status of reservation YOUR_RESERVATION_ID."}
```

`getReservationStatus` is a synchronous read tool protected by the service's async Resilience4j layer. Native Spring AI tool limits still apply. It should not poll repeatedly in one model execution.

For deterministic testing without model decisions:

- `POST http://localhost:8080/api/reservations`: same booking body including `paymentMethodToken`, and `Idempotency-Key` header. Returns 202 for pending states.
- `GET http://localhost:8080/api/reservations/{id}`: structured current result.
- `success: true` means the request/status lookup succeeded; **check `status` to determine whether the booking is confirmed**.

## Configuration

| Property | Default | Purpose |
|---|---|---|
| `server.port` / `PORT` | 9090 | Backend HTTP port |
| `spring.datasource.url` / `HOTEL_DB_URL` | file H2 database under `data/` | Durable local storage |
| `saga.worker-enabled` / `SAGA_WORKER_ENABLED` | true | Scheduling; false used in deterministic tests |
| `saga.poll-ms` / `SAGA_POLL_MS` | 1000 | Poll interval for workers |
| `saga.retry-ms` / `SAGA_RETRY_MS` | 2000 | Initial retry delay; exponential, capped at 60 seconds |
| `saga.lease-ms` / `SAGA_LEASE_MS` | 30000 | Recovery window for abandoned work; keep above worst-case processing duration |
| `saga.batch-size` / `SAGA_BATCH_SIZE` | 10 | Maximum events per polling batch |
| `payment.timeout-ms` / `PAYMENT_TIMEOUT_MS` | 500 | Per-payment HTTP connection/read timeout |
| `payment.base-url` / `PAYMENT_BASE_URL` | local `/simulator` | Provider adapter endpoint base |
| `simulator.enabled` / `SIMULATOR_ENABLED` | true | Enables demo payment/admin APIs |

The payment adapter contract is `GET /payments/{paymentKey}` for reconciliation and `POST /payments` for an idempotent charge, with body `{paymentKey,paymentMethodToken,amountCents,currency}` and `Idempotency-Key`. Responses use `SUCCEEDED`, `DECLINED` or `PENDING`. A real provider usually needs a provider-specific adapter rather than only a URL change. Raw credentials never belong in tool arguments, prompts, logs or outbox payloads.

Monitor `/actuator/health`, `/actuator/metrics/hotel.saga.payment.outcomes`, unresolved reservations, retry ages and queue backlogs. Logs include event type and error class, not booking bodies or tokens. Production needs alerting, audited operations, authentication/authorization, tenant-scoped keys, schema migrations, secret handling and real payment/notification integrations. These are deployment requirements beyond the local simulator, not implemented guarantees.

## Tests

```bash
mvn clean verify
```

Verified: **17 Hotel Service tests**, **60 companion AI tests** and **6 existing Python mock tests** pass. A browser checkout smoke test also reached `CONFIRMED`.

Tests use local HTTP, in-memory and file H2 databases, and the payment simulator. They cover atomic rollback, success, decline compensation, ambiguous timeouts before/after provider commit, pending reconciliation, concurrent replay, inventory contention, key conflicts, expired worker leases, crash after payment commit, independent notification consumers, duplicate delivery, HTTP payment collection/status contracts, and full application restart recovery. No real money, email, PMS or OpenAI calls occur.

The companion AI project additionally tests that payment tokens remain outside model-visible arguments/messages, pending results remain pending, and status reads work without write authorization context.

See [the architecture decision](docs/ADR-001-local-reservation-saga.md) for boundaries and trade-offs.


### Structured reservation IDs in chat responses

`POST /api/chat` returns backend reservation tool results in a `reservations` array, separately from the model's natural-language `message`. Use `reservations[0].reservationId` for a single booking; never scrape an ID from assistant wording. Each entry is a sanitized `ReservationResponse` and includes its business status. Payment tokens and guest details are not included.

```json
{
  "success": true,
  "message": "Your booking is accepted; payment confirmation is pending.",
  "errorCode": null,
  "reservations": [
    {
      "success": true,
      "reservationId": "YOUR_RESERVATION_UUID",
      "status": "PENDING_PAYMENT",
      "errorCode": null,
      "message": "Reservation accepted; payment confirmation is pending. Check reservation status.",
      "retryable": false
    }
  ]
}
```

Both creation and status tools populate this array with their actual service results. With several reservation calls, inspect all entries. An ordinary chat without reservation tool results returns an empty array. `PAYMENT_REQUIRED` results have no reservation ID because no booking exists yet. The top-level `success` describes the chat execution, not payment confirmation.

Results already obtained from a booking tool are retained even when a later model call fails or a duplicate/limit stops execution. In that case, top-level `success` can be false while `reservations` still contains a valid pending/confirmed booking. Check those results before deciding to retry a write.

After updating the AI project, restart it. To recover an ID from a previous response that omitted it, replay the identical request with the **same Idempotency-Key, token and booking details**, or POST those details directly to `/api/reservations`. Do not generate a new booking key to recover an existing reservation.

In Postman's **Scripts -> Post-response**, save the ID from the chat result:

```javascript
const reservation = (pm.response.json().reservations || [])
  .find(r => r.reservationId);
if (reservation) {
  pm.collectionVariables.set('reservationId', reservation.reservationId);
}
```

The included collection now does this automatically for **Chat: create via tool**. GET `/api/reservations/{{reservationId}}` to read the current state without another creation request.
