# Hotel availability and reservations — Spring AI 2.0.1

This is the **AI orchestration application on port 8080**, not the authoritative reservation backend. Choose one backend below. For the complete payment flow, use Java `hotel-service`.

Java 17+, Maven 3.9+, Spring Boot 4.1.1, Spring AI 2.0.1 and Resilience4j 2.4.0 (Boot 4 starter).

This project implements three separate protections: Spring AI native limits control tool-call quantity; orchestration fingerprints reject identical reservation write calls in the current execution; backend idempotency prevents repeated HTTP requests from creating another booking. Native tool counts still belong to Spring AI. No custom model-call, token/cost or overall execution budget has been added. The original Spring AI 1.1 project is a separate copy.

## Choose the backend first

| | Python `mock/hotel_api.py` in this project | Companion Java `hotel-service` |
|---|---|---|
| Use it for | Focused HTTP resilience and idempotency tests | Complete reservation/payment saga tests |
| Availability | Fixed mock room response | Database-backed inventory; overlapping bookings checked |
| Booking success | Immediately `CONFIRMED` | Initially `PENDING_PAYMENT`; worker later confirms or cancels |
| Payment | No payment processing; token not required | Simulated payment-token collection, charge and reconciliation |
| Database | SQLite for reservation replay/idempotency | File-backed H2 for inventory, reservations, payments and outbox |
| Failure scenarios | HTTP retry, timeout, 4xx/5xx and lost response after booking | Payment decline, uncertain payment outcome and restart recovery |
| Email/PMS | Not implemented | Independent durable simulated deliveries |
| Default port | 9090 | 9090 |

**Run exactly one backend on 9090.** The AI application points to either one through `HOTEL_API_BASE_URL`. The Python mock is still used automatically by focused automated tests; do not remove it just because you use Java for manual payment testing.

```text
Option A — full payment flow (recommended for current work):
Postman / frontend -> AI2 :8080 -> Java hotel-service :9090

Option B — focused resilience demonstration:
Postman / frontend -> AI2 :8080 -> Python hotel_api.py :9090
```

### Option A: start Java Hotel Service for the payment flow

Keep both project folders as siblings after cloning. From the repository root, use two terminals:

```bash
# Terminal 1: hotel backend
cd hotel-service
mvn spring-boot:run
```

```bash
# Terminal 2: AI application
cd hotel-availability-ai2
export HOTEL_API_BASE_URL='http://localhost:9090'
export OPENAI_API_KEY='your-valid-key'
mvn spring-boot:run
```

Stop the Python mock if it already owns 9090. No Docker is needed. The Java backend, checkout page and direct API tests work without OpenAI; `/api/chat` needs a valid OpenAI key.

Read the [Hotel Service guide](../hotel-service/README.md) for payment success, decline, timeouts, restart recovery and configuration. Import its [Postman collection](../hotel-service/postman/hotel-booking.postman_collection.json).

The browser page at `http://localhost:9090/` tests checkout **directly against the Java backend**. It is not an integrated chat frontend. To test assistant -> payment collection -> booking tool, use Postman: first chat without a token, collect one through `/simulator/payment-methods`, then repeat chat with the same booking key and a `Payment-Method-Token` header. Keep tokens outside `message`. The detailed [payment saga section](#payment-saga-integration-companion-java-hotel-service) below shows the requests.

### Option B: run the Python mock for resilience tests

The following mock setup and HTTP failure-mode examples use Python, not the Java saga backend. They do not exercise payment collection or the outbox.



Open two terminals in this project folder (the folder containing `pom.xml`). Python 3 is needed only for the local mock; no Python packages are required.

#### Terminal 1: start the Python hotel mock on port 9090

```bash
python3 mock/hotel_api.py --mode success
```

Leave this terminal running. The mock accepts:

```text
GET /hotels/h1/availability?checkIn=2027-01-01&checkOut=2027-01-02&guests=2
```

It returns HTTP 200 with `Content-Type: application/json`:

```json
[
  {
    "roomId": "r1",
    "roomType": "King",
    "price": 120.0,
    "currency": "USD"
  }
]
```

The upstream contract is a room array; `[]` means a successful search with no rooms. Prices and currency are provided by the hotel API. The reservation tool below creates a booking through the hotel provider; the included provider is a local SQLite mock. No payment is processed.

#### Terminal 2: start the AI application on port 8080

macOS/Linux:

```bash
export HOTEL_API_BASE_URL='http://localhost:9090'
export OPENAI_API_KEY='your-key'
mvn spring-boot:run
```

Windows PowerShell:

```powershell
$env:HOTEL_API_BASE_URL = 'http://localhost:9090'
$env:OPENAI_API_KEY = 'your-key'
mvn spring-boot:run
```

`OPENAI_MODEL` defaults to `gpt-4o-mini`. A live chat request needs a valid API key. Automated tests use simulated models and do not call OpenAI.

The earlier project also uses port 8080. Run one copy at a time, or use `mvn spring-boot:run -Dspring-boot.run.arguments=--server.port=8081` and send chat requests to port 8081.

## Test success in Postman

First send **GET**, with no body:

```text
http://localhost:9090/hotels/h1/availability?checkIn=2027-01-01&checkOut=2027-01-02&guests=2
```

Expect HTTP 200 with the room array. This checks the mock directly and does not exercise Spring AI or Java retries.

Then send **POST**:

```text
http://localhost:8080/api/chat
```

Set `Content-Type: application/json`. Select **Body → raw → JSON**:

```json
{
  "message": "Search availability at hotel h1 from 2027-01-01 to 2027-01-02 for 2 guests."
}
```

Expected response (assistant wording varies):

```json
{
  "success": true,
  "message": "A King room is available for USD 120.",
  "errorCode": null
}
```

Each HTTP chat request is stateless. Include the hotel ID, dates and guest count each time. The model decides whether to request a tool. `success` here means the chat completed; the assistant may also successfully explain that a hotel API search failed. The internal tool response retains its separate `success`, `rooms`, `errorCode`, `message` and `retryable` fields.

## Configure native Spring AI limits

Edit the existing `spring.ai.tools.limits` section in `src/main/resources/application.yml`:

```yaml
spring:
  ai:
    tools:
      limits:
        max-total-tool-calls: 3
        max-calls-per-tool-default: 2
        max-calls-per-tool:
          createReservation: 2
        on-limit-exceeded: THROW
```

- `max-total-tool-calls`: maximum individual tool invocations across all tools during a user turn.
- `max-calls-per-tool-default`: maximum invocations of each tool by name, whether arguments are the same or different.
- `on-limit-exceeded: THROW`: stops the native loop at the breached limit. The application maps Spring AI's native limit finish reason to a failed chat response.

Spring AI reconstructs counts from the current turn's conversation history. A new HTTP chat request starts a fresh turn and resets counts. No application counter tracks them. Both `searchAvailability` and `createReservation` allow at most two calls by default, subject to the shared cap of three. A reservation tool is attached to a chat request only when the client supplies an `Idempotency-Key` header. Identical reservation writes can be rejected earlier by fingerprinting.

To override one tool, add a native per-tool setting:

```yaml
spring:
  ai:
    tools:
      limits:
        max-total-tool-calls: 3
        max-calls-per-tool-default: 2
        max-calls-per-tool:
          searchAvailability: 1
        on-limit-exceeded: THROW
```

Modify the existing YAML section instead of adding a second `spring` key. Restart the app after editing. The old `agent.limits` settings are not used.

Identical read-only availability searches are allowed until the native count is exhausted. Identical reservation writes are rejected separately at the orchestration boundary before delegation to the native manager. Native limits check each invocation during batch execution. Earlier calls in a batch can complete before a later call is rejected; there is no rollback.

Spring AI also offers `RETURN_ERROR_RESPONSE`, which feeds an error result back to the model and continues the conversation. Keep `THROW` for this project's stop-at-limit behavior. Native tool counts do not provide an overall execution deadline or token/cost budget. The OpenAI `timeout: 20s` setting is a transport timeout for an individual model request, not an overall chat deadline; `max-retries: 0` disables SDK retries.

### Check a per-tool limit in Postman

Stop Spring Boot with Ctrl+C, then restart with one allowed call per tool:

```bash
mvn spring-boot:run "-Dspring-boot.run.arguments=--spring.ai.tools.limits.max-total-tool-calls=3 --spring.ai.tools.limits.max-calls-per-tool-default=1"
```

Send this JSON to the same POST endpoint:

```json
{
  "message": "Search hotel h1 and hotel h2, both from 2027-01-01 to 2027-01-02 for 2 guests. Search each hotel separately."
}
```

If the model requests both searches, the first runs and the second is blocked. The mock accepts both hotel IDs and returns the same example room. Expect a failed chat response:

```json
{
  "success": false,
  "message": "Tool call limit (1) exceeded for tool 'searchAvailability'. No further calls to this tool are allowed in this turn.",
  "errorCode": "TOOL_CALL_LIMIT_EXCEEDED"
}
```

This is HTTP 200 with `success: false`. The message is produced by Spring AI. No additional model request is made to explain the limit. The response has only `success`, `message` and `errorCode`.

### Check a total tool limit in Postman

Restart Spring Boot with:

```bash
mvn spring-boot:run "-Dspring-boot.run.arguments=--spring.ai.tools.limits.max-total-tool-calls=1 --spring.ai.tools.limits.max-calls-per-tool-default=5"
```

Send the same two-hotel JSON. If the model requests two calls, the second is blocked by the total cap. Expect `errorCode: TOOL_CALL_LIMIT_EXCEEDED` and a message about the total tool-call limit. Model behavior varies, so automated tests are the deterministic check. Restart with the normal `mvn spring-boot:run` command afterward to restore YAML defaults.

## Hotel mock modes

Stop terminal 1 with **Ctrl+C**, then restart with one mode. Keep Spring Boot running while changing mock modes. Only run one mock instance on port 9090 at a time.

| Mode | Command | Behavior |
|---|---|---|
| Success | `python3 mock/hotel_api.py --mode success` | Immediate HTTP 200 with a room array |
| Retry recovery | `python3 mock/hotel_api.py --mode retry` | First two requests return 503, later requests return 200 |
| Persistent failure | `python3 mock/hotel_api.py --mode failure` | Always HTTP 503 |
| Bad request | `python3 mock/hotel_api.py --mode bad-request` | Always HTTP 400 |
| Slow response | `python3 mock/hotel_api.py --mode timeout` | Delay each response by 3 seconds |
| Committed response loss | `python3 mock/hotel_api.py --mode commit-then-fail` | First write commits a booking but returns 503; same-key retry replays it |
| Committed response timeout | `python3 mock/hotel_api.py --mode commit-then-timeout` | First write commits, then delays its response; same-key retry replays it |

For the two main cases:

```bash
python3 mock/hotel_api.py --mode success
```

```bash
python3 mock/hotel_api.py --mode timeout
```

In retry mode, send the chat request once and look for these Spring Boot logs:

```text
Availability retry attempt=1
Availability retry attempt=2
```

That means three total hotel HTTP attempts within **one** Spring AI tool invocation. Restarting the mock resets its attempt counter. Do not call the mock directly before a retry test, because direct requests consume its first two failures too.

Persistent 503 failures eventually map to `TEMPORARILY_UNAVAILABLE`. HTTP 400 maps to `INVALID_REQUEST` without internal retries. These are internal availability tool codes; the chat response contains the assistant's explanation.

In timeout mode, the default 1500ms HTTP read timeout normally fires before the 2-second Resilience4j time limiter. Expect a retried transport failure with internal code `TEMPORARILY_UNAVAILABLE`. To exercise the Resilience4j `TIMEOUT` path, restart Spring Boot with a shorter per-attempt limit:

```bash
mvn spring-boot:run -Dspring-boot.run.arguments=--resilience4j.timelimiter.instances.availability.timeoutDuration=1s
```

Calling port 9090 directly bypasses Java timeouts: Postman can receive HTTP 200 after 3 seconds. A Postman timeout of 60 seconds gives manual chat tests time for the model calls and hotel retries. Restart Spring Boot normally after temporary overrides.

## Architecture

```text
POST /api/chat
 → ChatService calls ChatClient
 → native ToolCallingAdvisor manages model/tool rounds and history
 → WriteFingerprintingToolCallingManager rejects repeated reservation calls in this execution
 → native DefaultToolCallingManager checks total/per-tool counts
 → synchronous HotelTools / ReservationTools @Tool
 → AvailabilityService
 → CircuitBreaker [ Retry [ TimeLimiter [ async HTTP task ] ] ]
 → HotelApiClient / RestClient → hotel API
 → structured tool result → model → final chat response
```

`ChatConfig` wraps Spring AI's auto-configured manager with `WriteFingerprintingToolCallingManager`. The wrapper only checks normalized write fingerprints; it delegates counting, execution and history to the native manager. `HotelTools` is always registered; `ReservationTools` is attached per request with a server-side execution context when the client provides an idempotency key. The model never supplies that key as a tool argument. Spring AI still owns the loop; no manual loop or general budget machinery is present. `ChatService` maps native limit responses and duplicate exceptions to controlled HTTP responses and sanitizes unexpected provider errors.

| Package | Responsibility |
|---|---|
| `chat` | Endpoint, simple chat service and response |
| `config` | Native ChatClient setup, hotel HTTP client/executor and event hooks |
| `tool` | Synchronous availability and reservation `@Tool` methods |
| `reservation` | Write orchestration fingerprinting, per-execution state, idempotency-key validation and direct POST endpoint |
| `dto` | Search parameters, room and availability result |
| `service` | Separate availability/reservation async resilience and safe failure mapping |
| `client` | Hotel RestClient adapter |
| `exception` | Hotel validation, transient and contract failures |

The hotel resilience layer remains separate from Spring AI's tool-call counting. Explicit aspect orders 1/2/3 make the circuit breaker outermost, so it counts one logical search after retries. The gateway propagates the final failure directly, and `AvailabilityService` provides the structured fallback response. A pass-through annotation fallback is unnecessary. The dedicated availability executor is bounded to 8–16 workers with a queue of 16; HTTP timeouts bound blocking work that may continue after future cancellation.

## Tests and observability

Validation results for the reservation extension are recorded at the end of this README. No automated tests call OpenAI or a live hotel provider.

Run all tests and package the executable JAR:

```bash
mvn clean verify
```

The tests use local simulated HTTP servers and models. **Python 3 (`python3`) must be on PATH**: reservation integration tests start their own SQLite hotel provider process in a temporary directory. No OpenAI key, Docker or separately running mock is needed.

```bash
mvn -Dtest=NativeTotalLimitIntegrationTest test
mvn -Dtest=NativePerToolLimitIntegrationTest test
mvn -Dtest=ChatHttpIntegrationTest test
mvn -Dtest=AvailabilityIntegrationTest#retriesTransientErrorsThenSucceeds test
mvn -Dtest=AvailabilityIntegrationTest#exhaustedRetriesAreOneCircuitFailure test
```

Native configuration tests confirm that the actual auto-configured Spring AI manager reads the properties and resets counts for a new user request. The real OpenAI HTTP adapter test uses a repeating simulated model: two hotel calls execute, and the third requested call is rejected by the native per-tool cap. Other tests cover normal completion, native batch handling, input validation, hotel retries/timeouts/circuit opening, and sanitized failures.

Send **GET** `http://localhost:8080/actuator/health` for app health; expect `{"status":"UP"}`. This does not test the external model or hotel API. `/actuator/prometheus` exposes JVM, HTTP, Resilience4j and `hotel.availability.results` metrics. Spring AI provides native tool observations. Application hooks log circuit transitions and hotel retries without raw chat messages, guest details, idempotency keys or upstream error bodies.

## Troubleshooting

- **9090 is unreachable:** confirm terminal 1 is running and nothing else occupies the port. Test the direct GET before chat.
- **9090 responds slowly:** timeout mode intentionally delays responses; restart with `--mode success` for an immediate result.
- **Circuit remains open:** wait at least 20 seconds or restart Spring Boot. Restarting only the mock does not reset the Java circuit breaker.
- **8080 is unreachable:** check Spring Boot startup logs, API key configuration and port conflicts.
- **Only one search runs in a two-hotel test:** the model may choose one call. Use native integration tests to verify limits deterministically.
- **Spring Boot runs inside Docker:** localhost refers to that container. This walkthrough assumes both processes run directly on your computer; the Python mock binds to 127.0.0.1.

References: [Spring AI native tool limits](https://docs.spring.io/spring-ai/reference/api/tools.html#_tool_call_limits), [ToolCallingAdvisor](https://docs.spring.io/spring-ai/reference/api/tools/tool-calling-advisor.html), [Resilience4j Spring Boot setup](https://resilience4j.readme.io/docs/getting-started-3).

## Reservation POST and the three protections

```text
Spring AI native total/per-tool limits control quantity
  → orchestration: tool name + normalized reservation arguments → SHA-256 fingerprint
  → reject duplicate writes in the current execution, before tool/backend dispatch
  → ReservationTools uses the client's stable operation key from ToolContext
  → ReservationService → Resilience4j → POST /reservations + Idempotency-Key
  → hotel provider atomically stores booking + key/payload/result
```

| Protection | Scope | Owner |
|---|---|---|
| Native tool-call limits | Current user turn; reads and writes | Spring AI DefaultToolCallingManager |
| Identical-write fingerprints | Current chat execution; reservation writes only | WriteFingerprintingToolCallingManager + ReservationExecution |
| Idempotency | HTTP retries, concurrent executions, other clients and provider restarts | Hotel provider; SQLite implementation in the included mock |

`ReservationFingerprint` parses JSON with duplicate-key detection, rejects extra fields, validates the booking and hashes the tool name plus canonical arguments. JSON property order, `2` versus `2.0`, and normalized surrounding whitespace do not bypass detection. Guest name whitespace is collapsed; identifier and email case is preserved. The original payload and fingerprints are not logged.

Each chat execution gets a new `ReservationExecution`, passed through internal ToolContext. Its synchronized set is checked/claimed before dispatch; a repeated fingerprint throws `DuplicateToolCallException`. Failed or uncertain attempts also remain claimed, since another side effect may already exist. Duplicate writes in the same model batch are rejected before any backend call in that batch. The exception terminates the loop and returns `DUPLICATE_TOOL_CALL` directly, without another model request. Availability reads are not fingerprinted. Native quantity limits remain framework-owned and can also stop execution.

### Idempotency contract: client operation identity, not model-generated arguments

The client must generate an opaque unique **Idempotency-Key before its first booking attempt**, persist it, and reuse it for every retry/replay of that same operation. Supported keys contain 8–128 ASCII letters/digits/dots/underscores/colons/hyphens. A UUID is suitable. The model cannot choose or replace the key: it is not in the tool schema and comes only from the HTTP header.

- Same key + same normalized booking details → return the original reservation ID/result without creating again.
- Same key + different booking details → HTTP 409 `IDEMPOTENCY_CONFLICT`; do not retry with changed details under that key.
- Different key → a new booking operation, even when its details happen to match a previous booking.

One key represents **one booking**, not an entire multi-booking itinerary. Send separate HTTP requests with separate operation keys for intentionally different reservations. If a model changes booking details after a write, the same-key backend conflict prevents another side effect.

The Java service forwards the same immutable key on **every** Resilience4j attempt. It does not generate keys inside a retry, hash booking contents into a globally reusable key, or use an in-memory cache as its idempotency boundary. `ReservationExecution` holds request-local booking context, duplicate fingerprints and sanitized tool results; it does not provide cross-request backend idempotency. A new chat execution may legitimately replay the same key to recover a result. The backend remains the final boundary.

**Required upstream contract:** the real hotel service must atomically enforce the key, compare a canonical request hash, and retain the reservation outcome durably for its documented replay window. A header alone cannot provide idempotency if the provider ignores it. Enable write retries only against a provider that implements this contract. The adapter currently treats upstream 409 as an idempotency conflict; adapt that mapping if a real provider distinguishes other conflict types. In a multi-tenant service, bind key scope and booking authorization to the authenticated tenant/account; the local reference app does not implement tenant authentication.

### Persistent local provider

The mock implements the provider contract in `mock/hotel_api.py`. Start it with:

```bash
python3 mock/hotel_api.py --mode success --db mock/reservations.sqlite3
```

The database contains the booking, request hash and original response under a unique idempotency key. `BEGIN IMMEDIATE` and a primary-key constraint serialize competing requests across threads/processes. The booking row and idempotency result are committed in the **same SQLite transaction**, avoiding a gap between creating the reservation and recording its key. The mock has no expiration; deleting the database deletes both booking data and replay protection. Restart with the same database path to preserve results.

This is a local demonstration provider, not a real inventory/payment engine. The SQLite database contains guest details and is excluded from source archives and Git. The `/admin/*` endpoints below are local mock/test controls only.

### Test the write API directly in Postman (no model call)

Keep Spring Boot running and send **POST**:

```text
http://localhost:8080/api/reservations
```

Headers:

```text
Content-Type: application/json
Idempotency-Key: booking-demo-001
```

Body → raw → JSON:

```json
{
  "hotelId": "h1",
  "roomId": "r1",
  "checkIn": "2027-01-01",
  "checkOut": "2027-01-02",
  "guests": 2,
  "guestName": "Alex Guest",
  "guestEmail": "alex@example.com"
}
```

Expected HTTP 200:

```json
{
  "success": true,
  "reservationId": "res-<generated-id>",
  "status": "CONFIRMED",
  "errorCode": null,
  "message": "Reservation confirmed.",
  "retryable": false
}
```

1. Send again with the **same header and body**: the reservation ID remains identical.
2. Keep the key but change `roomId` to `r2`: expect HTTP 409 with `errorCode: IDEMPOTENCY_CONFLICT` and no additional booking.
3. Change to a new key only when intentionally requesting a new booking operation.
4. Send GET `http://localhost:9090/admin/reservations` to inspect `reservationCount`, `reservationIds` and POST attempt count. Compare counts before and after your test; earlier tests may already have bookings.

This direct endpoint does not call OpenAI or consume Spring AI tool limits. App startup still needs a configured API key; for direct local testing only, `OPENAI_API_KEY=test-key` allows startup, but that dummy key will not work for real chat requests. A missing/invalid Idempotency-Key is rejected without dispatching a write. Invalid booking JSON returns 400.

### Test the chat reservation tool in Postman

Use a valid OpenAI key for live chat. Send **POST** `http://localhost:8080/api/chat` with:

```text
Content-Type: application/json
Idempotency-Key: booking-chat-001
```

```json
{
  "message": "Please create a reservation at hotel h1 for room r1, from 2027-01-01 to 2027-01-02, for 2 guests. Guest name: Alex Guest. Guest email: alex@example.com."
}
```

The reservation tool is only attached when the header is present and valid. The tool remains synchronous, with async resilience below it. Only explicitly requested bookings with complete details should be created. The model's wording/tool selection is provider-dependent.

A repeated write **within this one chat execution** returns:

```json
{
  "success": false,
  "message": "An identical reservation was already attempted in this execution. No additional booking request was sent. Reconcile or replay using the same Idempotency-Key.",
  "errorCode": "DUPLICATE_TOOL_CALL"
}
```

This does not mean the first booking failed—it may already be confirmed. Replaying the direct POST with the same key/details retrieves the original result. Clicking Send again creates a **new execution** and therefore tests backend idempotency, not within-execution fingerprinting. A real model may refuse a request to book twice; use `ReservationChatIntegrationTest` for a deterministic repeating model.

### Verify committed-response loss and timeout handling

Stop the mock and restart with the **same DB path**:

```bash
python3 mock/hotel_api.py --mode commit-then-fail --db mock/reservations.sqlite3
```

Use a fresh operation key and send the direct reservation POST once. The backend commits a booking but returns 503 on the initial attempt. Resilience4j retries using the same key; the provider returns the original reservation. Expect success, two upstream POST attempts and exactly one new booking.

Repeat with a fresh operation key in this mode:

```bash
python3 mock/hotel_api.py --mode commit-then-timeout --db mock/reservations.sqlite3
```

The first write commits, then delays its response by three seconds. The Java HTTP read times out; a same-key retry replays the committed reservation. Again, only one side effect occurs. A reservation has a separate circuit breaker, bounded reservation executor, three maximum HTTP attempts and a 2-second per-attempt time limiter, with the same shorter HTTP connect/read bounds as availability.

If the outcome cannot be established after retries, the service returns `RESERVATION_OUTCOME_UNKNOWN` (`retryable: true`) rather than falsely claiming the reservation failed. The direct endpoint returns HTTP 503 in that case. Keep and reuse the **same** key for reconciliation; do not generate a new key after a timeout. Native tool limits count one logical reservation invocation; internal HTTP retries do not consume extra tool slots.

### Reservation tests

```bash
mvn -Dtest=ReservationFingerprintTest test
mvn -Dtest=ReservationChatIntegrationTest test
mvn -Dtest=ReservationIntegrationTest test
mvn clean verify
python3 -m unittest discover -s mock -p 'test_*.py'
```

The Java tests start a real Python/SQLite provider in a temporary directory and cover HTTP replay, payload conflict, concurrent clients, 503/read-timeout after commit, non-retryable 400, open circuit and direct endpoint validation. Chat tests prove the orchestration rejects identical normalized writes before a second POST, rejects duplicate batches before any POST, keeps executions independent, leaves quantity limits to Spring AI, and never exposes or dispatches the write tool without a valid client operation key. Unit tests cover fingerprint normalization, atomic claim behavior, unknown outcomes and interruption.

The six Python tests additionally verify replay across a fresh provider process and concurrent provider processes sharing the same database. No test creates a live hotel reservation or calls OpenAI.

## Latest validation

- `mvn clean verify`: **60 Java tests passed**, zero failures/errors; executable JAR built.
- `python3 -m unittest discover -s mock -p 'test_*.py'`: **6 Python tests passed**, including persistent replay and concurrent provider processes.
- All writes used isolated local mock databases; no live reservation or OpenAI call was made.

## Payment saga integration (companion Java Hotel Service)

See [Hotel Service run instructions](../hotel-service/README.md) for the complete setup. Docker is not needed. Stop the Python mock before starting Java on 9090; both cannot use the same port.

The local checkout simulator at `http://localhost:9090/` collects a simulated `pm_...` payment-method token without card details. It creates reservations directly in the Hotel Service. For testing **AI tool calls**, use the token collection endpoint separately, then send `Payment-Method-Token` and `Idempotency-Key` headers to `/api/chat`; do not paste a token into `message`. The server places these values in request context. The model-visible `createReservation` arguments remain the seven booking fields.

```http
POST http://localhost:9090/simulator/payment-methods
```

Copy the returned token:

```http
POST http://localhost:8080/api/chat
Content-Type: application/json
Idempotency-Key: booking-payment-chat-0001
Payment-Method-Token: pm_COPY_THE_RETURNED_TOKEN

{"message":"Book hotel h1 room r1 from 2027-01-01 to 2027-01-02 for 2 guests, guest Alex Guest, alex@example.com."}
```

Without payment context, the Java backend returns `PAYMENT_REQUIRED`, and the assistant should direct the guest to secure checkout. No reservation is created at that point. This check is enforced by the Java Hotel Service; the old Python mock deliberately does not enforce payment collection.

`createReservation` can return `PENDING_PAYMENT`, `PAYMENT_UNKNOWN`, `CONFIRMED` or a replayed `CANCELLED` result. **An accepted request is not proof of a confirmed reservation.** The `success` field indicates successful submission/lookup; `status` is the business state. The model must preserve this distinction. It can use the synchronous `getReservationStatus` read tool, available without a write idempotency key:

```http
POST http://localhost:8080/api/chat
Content-Type: application/json

{"message":"Check the status of reservation YOUR_RESERVATION_ID."}
```

Both tools retain their service-level Resilience4j timeout/retry/circuit-breaker layer. Native Spring AI tool limits apply to status reads too (default total 3, per-tool 2). Write fingerprinting still targets only `createReservation`. Checking status must not trigger a repeated reservation write.

For deterministic tests without a model, POST `/api/reservations` with the original seven fields **plus** `paymentMethodToken` in the JSON body and an `Idempotency-Key` header. A pending booking returns HTTP 202. GET `/api/reservations/{id}` returns its current structured result. Status and tool results omit payment tokens.

Keep the same booking details, payment token and key for network retries. A changed token under the same key is a different payload and is rejected. Do not collect another payment method just to check status or retry an uncertain response.

The local backend persists its saga, inventory holds, payment idempotency and outbox state across restarts. Payment HTTP runs outside database transactions. Email/PMS processing is simulated with independent durable deliveries; actual AWS, email, real payment and PMS connections are not included. See the companion README for the intended SNS -> two SQS queues deployment boundary and operational limitations.


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

## Simple code map

Read the application in this order:

| Location | Responsibility |
|---|---|
| `chat/ChatController` -> `ChatService` | Accept chat input, supply backend-only context and call Spring AI |
| `tool/HotelTools` | Synchronous availability tool |
| `tool/ReservationTools` | Synchronous booking tool; exposed only with a valid operation key |
| `tool/ReservationStatusTools` | Read-only reservation status tool; available without a booking key |
| `service/*Service` | Convert final service results/failures into safe structured responses |
| `service/Resilient*Gateway` | Async boundary with Resilience4j retry, timeout and circuit breaker |
| `client/HotelApiClient` | Make HTTP requests to the selected Java backend or Python mock |
| `reservation/ReservationExecution` | One request's key, token, duplicate fingerprints and returned reservation results |
| `reservation/ReservationFingerprint` | Canonicalize write arguments and calculate their fingerprint |
| `reservation/WriteFingerprintingToolCallingManager` | Reject duplicate writes before Spring AI dispatches them; native limits stay in Spring AI |
| `reservation/ReservationController` | Direct create/status APIs for deterministic testing without a model |
| `config/HotelApiConfig` | HTTP client and separate bounded availability/reservation executors |
| `config/ChatConfig`, `ResilienceLogging` | Model/tool configuration and resilience event logging |
| `dto/`, `exception/` | API contracts and distinctions between rejected, transient and invalid upstream results |

The read and write reservation tool classes remain separate deliberately: exposing status reads must not expose booking writes without a valid key. Exception types are used by the retry/circuit-breaker configuration; combining them would change failure handling. The Python mock remains because the automated resilience tests use it.

The cleanup removes the separate `ReservationResults` class, the availability gateway's pass-through fallback, unused constructors and a placeholder logging bean. Request data/results now share `ReservationExecution`, executor setup uses one small factory, and reservation HTTP status mapping uses an explicit switch. API fields, native limits, duplicate protection, payment-token handling and backend idempotency remain the same.
