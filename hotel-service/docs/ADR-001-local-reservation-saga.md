# ADR-001: Hotel-owned saga with a transactional outbox

**Status:** Accepted for the local simulator
**Date:** 2026-10-22
**Deciders:** User and project implementation

## Context

The Spring AI application already controls tool quantity, detects duplicate write calls per execution and sends stable reservation idempotency keys. Reservations now require payment, inventory consistency and asynchronous Email/PMS notification. The user chose a local payment simulator. No live cloud or payment credentials are configured.

## Decision

The Hotel Service owns the business workflow. Use explicit Spring `TransactionTemplate` boundaries and JDBC with a file-backed H2 database for this local implementation. Creation writes reservation, held inventory and a payment outbox event atomically. Workers call payment HTTP outside those transactions and persist transitions. Confirmation and its event commit atomically. Unknown payments retain inventory and reconcile; definitive decline cancels and releases it.

Collect a simulated payment token through a separate UI/API. Forward it in server context to the booking tool; never include it in model-visible tool arguments or results. There is no real card collection or charge.

Use a leased, durable outbox and independent durable notification deliveries as the local substitute for SNS plus two SQS queues. Each consumer has event-ID deduplication and a separate transaction. Real external side effects still require downstream idempotency.

## Options considered

| Option | Assessment |
|---|---|
| Distributed transaction across database, payment, Email and PMS | Not supported by these independent systems; couples availability and failures |
| In-memory future/task after booking | Simple, but loses work on process failure |
| Hotel-owned persisted saga and outbox | Chosen: restart recovery and explicit uncertain states |
| Full AWS SNS/SQS integration immediately | Unnecessary for the user-selected local simulator; deferred behind documented transport boundary |

## Consequences

The tool returns accepted/pending state and a reservation ID; status lookup returns the eventual outcome. Native tool limits and service Resilience4j remain separate from durable saga retries. Embedded H2 is suitable for local single-process execution, not a demonstrated multi-instance production deployment.

Unknown payments intentionally retain their hold. This version has no automatic hold expiry, cancellation API or refund state machine. Those policies must be implemented together, with reconciliation of late payment success, before real money is involved. PMS is a noncritical replication consumer; authoritative PMS confirmation requires another saga step.

Schema initialization is idempotent for this fixed local schema; evolving production schemas requires migrations. Simulator payment storage shares the database process only as a test convenience, while HTTP execution and transactions are independent.

## Follow-up work for a live deployment

- Provider-hosted checkout, backend token/session ownership validation and verified webhook processing.
- Shared production database, migrations, tenant-scoped idempotency and endpoint authorization.
- Persisted cancellation/expiry/refund compensation and operational escalation.
- Real event publisher and separate Email/PMS queues, dead-letter queues and visibility settings.
- Provider idempotency for outbound notifications; monitoring and recovery runbooks.

## References

- [Spring TransactionTemplate](https://docs.spring.io/spring-framework/reference/data-access/transaction/programmatic.html)
- [AWS transactional outbox](https://docs.aws.amazon.com/prescriptive-guidance/latest/cloud-design-patterns/transactional-outbox.html)
- [AWS SNS fan-out to SQS](https://docs.aws.amazon.com/sns/latest/dg/sns-sqs-as-subscriber.html)
