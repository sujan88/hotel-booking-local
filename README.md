# Hotel booking assistant: project guide

Start here after cloning. This repository contains an AI application and two alternative local hotel backends. **For the complete payment and reservation flow, run AI2 plus Java Hotel Service.**

## Recommended repository layout

```text
README.md
hotel-availability-ai2/           # Spring AI 2.0.1 application, port 8080
  README.md
  pom.xml
  mock/hotel_api.py               # Alternative Python backend, port 9090
hotel-service/                    # Java reservation/payment saga, port 9090
  README.md
  pom.xml
  postman/hotel-booking.postman_collection.json
```

Keep the two project folders next to each other so documentation links work. This guide and both folders are included in `hotel-booking-local.zip`. The older `hotel-availability` project, if present in the workspace, is the earlier Spring AI 1.1 example; it is not needed for the AI2 payment flow. Build output, local databases and ZIP archives do not need to be committed as source. Each project contains its own `.gitignore`.

## Understand the three components

| Component | Responsibility | Run it when |
|---|---|---|
| `hotel-availability-ai2` | Chat endpoint, synchronous Spring AI tools, native tool limits, write fingerprinting and Resilience4j around backend HTTP calls | You want to test the assistant or its direct proxy APIs |
| `hotel-service` | Authoritative local inventory, reservations, payment saga, reconciliation and outbox | You want the complete payment/reservation flow |
| `hotel-availability-ai2/mock/hotel_api.py` | Small HTTP simulator with SQLite-backed reservation idempotency | You want focused success/retry/timeout/HTTP-error demonstrations |

Java Hotel Service and the Python mock are **alternatives**, not two services in the same booking chain. They both default to 9090, so run only one. The Java service does not call the Python mock. The Python mock does not call the Java service.

```text
Full payment flow:
User / Postman -> AI2 :8080 -> Java Hotel Service :9090
                                 -> simulated payment HTTP
                                 -> outbox -> separate simulated Email/PMS deliveries

Focused resilience tests:
User / Postman -> AI2 :8080 -> Python hotel_api.py :9090
```

## Run the complete payment flow

Prerequisites: Java 17+, Maven 3.9+. Docker and AWS are not required. A valid OpenAI key is required only for live `/api/chat` requests.

From the repository root, start two terminals:

```bash
# Terminal 1
cd hotel-service
mvn clean verify
mvn spring-boot:run
```

```bash
# Terminal 2
cd hotel-availability-ai2
export HOTEL_API_BASE_URL='http://localhost:9090'
export OPENAI_API_KEY='your-valid-key'
mvn spring-boot:run
```

The [AI README](hotel-availability-ai2/README.md) also includes PowerShell environment-variable syntax. Stop the old Python mock first if port 9090 is occupied.

### Payment flow checklist

1. Import the [Postman collection](hotel-service/postman/hotel-booking.postman_collection.json).
2. Ask `/api/chat` to book with complete guest details and an `Idempotency-Key`, but no payment token. The assistant should explain that payment is required; the Java backend creates no reservation yet.
3. POST `http://localhost:9090/simulator/payment-methods` to simulate secure payment collection. Do not enter real card details. The simulator generates a fake `pm_...` token; the LLM does not generate it.
4. Repeat the chat request with the **same booking key and details**, supplying the collected token in the `Payment-Method-Token` header, outside the chat message.
5. Read the actual booking ID from **`reservations[0].reservationId`** in the chat response. The natural-language message may omit it. `PENDING_PAYMENT` is not confirmation; top-level chat `success: true` does not mean payment succeeded.
6. GET `http://localhost:8080/api/reservations/{id}`, or ask the status tool through chat, to retrieve the eventual `CONFIRMED`, `CANCELLED` or unresolved status.
7. GET `http://localhost:9090/simulator/summary` to inspect simulated payments, holds, notification counts and outbox processing.

The Postman chat-create request saves the structured ID automatically. For a network retry or recovery of an existing booking ID, retain the same key, token and request details. Do not generate a new key to check status. A changed key is a new business operation.

The browser page at **http://localhost:9090/** tests checkout directly against the Java backend. It collects a simulated token and creates a reservation without involving the LLM. An automatic chat-to-checkout frontend handoff is not implemented; use the Postman sequence above to test those boundaries manually.

Payment, Email and PMS are **local simulations**. The confirmed event fans out through durable local deliveries. Real AWS deployment would use an outbox publisher -> SNS topic -> separate Email and PMS SQS queues. Real payment, AWS, email and PMS integrations are not connected.

## Run focused Python mock scenarios instead

Stop Java Hotel Service first. From `hotel-availability-ai2`:

```bash
python3 mock/hotel_api.py --mode success
```

Or stop that process and restart it in timeout mode:

```bash
python3 mock/hotel_api.py --mode timeout
```

Keep AI2 on 8080 pointing at `http://localhost:9090`. These modes test HTTP resilience, not payment. A successful Python booking is immediately `CONFIRMED`, and no payment token is required. Python 3 is also needed by the AI project's automated tests; those tests start isolated mock processes automatically, so do not delete `mock/`.

## Detailed guides and tests

- [AI application: backend comparison, native tool limits, fingerprinting, resilience and chat responses](hotel-availability-ai2/README.md)
- [Hotel Service: complete Postman requests, payment modes, recovery, configuration and limitations](hotel-service/README.md)
- [Saga architecture decision](hotel-service/docs/ADR-001-local-reservation-saga.md)

```bash
# Run separately from the matching project directory:
mvn clean verify

# Additional Python mock tests, from hotel-availability-ai2:
python3 -m unittest discover -s mock -p 'test_*.py'
```

Verified: 60 AI Java tests, 17 Hotel Service Java tests and 6 Python tests. Automated tests use local simulators and do not call OpenAI, charge money, send email or contact a PMS. The checkout browser smoke test also reached `CONFIRMED`.

## Common confusion

| Symptom | Check |
|---|---|
| Port 9090 is already in use | Stop the other backend; choose Java or Python |
| Booking confirms immediately without payment | You may be calling the Python mock |
| Chat says accepted but has no ID in prose | Read `reservations[].reservationId`; update/restart AI2 if that field is missing |
| Chat returns 401 from OpenAI | Configure a valid key in the terminal starting AI2; direct backend/UI tests do not need it |
| New booking reports unavailable inventory | A confirmed or unresolved booking holds that room/date interval; choose another available interval |
| Restart did not clear bookings | Persistence is intentional; see the Java guide for an explicit local reset |
| I want to see the pending state longer | Increase `SAGA_RETRY_MS` for reconciliation scenarios; see the Hotel Service guide |
