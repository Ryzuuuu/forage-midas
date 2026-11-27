# Midas Core

[![CI](https://github.com/Ryzuuuu/forage-midas/actions/workflows/ci.yml/badge.svg)](https://github.com/Ryzuuuu/forage-midas/actions/workflows/ci.yml)

A transaction processing service in Java 17 and Spring Boot. It reads money transfers from Kafka,
validates them, asks an external Incentive API for a bonus, applies the transfer to an H2 database under
row locks, and serves balances and transaction history over REST.

Built on the starter code of the JPMorgan Chase Advanced Software Engineering program on Forage, then
extended with exact money arithmetic, idempotent consumption, concurrency control, retries and a test suite.

## How a transaction flows

```
 Kafka topic                 TransactionListener          TransactionProcessor                 Ledger (one DB transaction)
 midas-transactions  ──────▶  (topic, partition,   ──────▶  1. already processed? skip  ──────▶  lock both rows, lowest id first
 {senderId,                    offset) as an                2. validate (no locks)                re-check funds under the lock
  recipientId,                 idempotency key              3. call Incentive API                 debit sender, credit recipient
  amount}                                                      (no locks held)                    + incentive, write audit row
                                                                                                         │
                                                         GET /balance?userId=…   ◀───── H2 ◀─────────────┘
                                                         GET /users/{id}/transactions
```

## Design decisions

| Concern | What the code does | Where |
| --- | --- | --- |
| Money | `BigDecimal` with two decimal places in the database and in all arithmetic. Floats only appear at the edges, because the provided message and API contracts use them | `UserRecord`, `TransactionProcessor` |
| Concurrent transfers | `SELECT ... FOR UPDATE` on both accounts, always in ascending id order so opposite transfers cannot deadlock. The balance is checked again after the lock is taken. `@Version` optimistic locking stays on as a second safety net | `Ledger`, `UserRepository` |
| At least once delivery | Every message gets an audit row keyed by a unique `(topic, partition, offset)`. A redelivered message is skipped, and a concurrent duplicate is caught by the unique constraint | `TransactionRecord`, `TransactionProcessor` |
| Slow external API | The Incentive API is called with connect and read timeouts, before any row is locked, so a slow network call never holds a database lock | `IncentiveClient` |
| Failures | If the Incentive API is down nothing is written, and the Kafka error handler retries with a fixed back off. A message that still fails is logged with its exact coordinates and the partition keeps moving. Malformed JSON is not retried | `KafkaErrorHandlingConfig` |
| Auditability | Rejected transactions are stored too, with a reason: `UNKNOWN_SENDER`, `UNKNOWN_RECIPIENT`, `SAME_ACCOUNT`, `NON_POSITIVE_AMOUNT`, `INSUFFICIENT_FUNDS` | `RejectionReason` |

## REST API (port 33400)

| Method | Path | Returns |
| --- | --- | --- |
| GET | `/balance?userId=7` | `{"amount": 444.55}`. An unknown user has a balance of 0, as the Midas contract requires |
| GET | `/users/7/transactions?page=0&size=20` | Accepted and rejected transactions where the user is sender or recipient, newest first. 404 for an unknown user, 400 for a page size above 100 |
| GET | `/actuator/health` | Service health |

## Tests

```bash
./mvnw verify
```

23 tests run by default:

- **TransactionProcessorTest** (Spring context with H2): valid transfers, every rejection reason, exact
  whole-balance transfers, redelivery of the same message, recovery after an Incentive API outage, and a
  race of 8 threads sending 200 transfers against a balance that covers only 100 of them. Exactly 100 are
  accepted and no update is lost. Without the row lock this test fails.
- **IncentiveClientTest**: a real local HTTP server, including server errors and a read timeout.
- **BalanceControllerTest**: the REST contract with MockMvc.
- **MidasIntegrationTest**: messages on an embedded Kafka broker, through the listener and the database,
  out of the REST API.

The original Forage verifiers (`Task*Tests`) wait for a debugger and never finish on their own, so they
are excluded from the default build. Run one with `./mvnw test -Pforage -Dtest=TaskFiveTests`.
Task Five also needs the Incentive API running (see below).

## Run it locally

Needs Java 17 or newer. Kafka must be reachable at `localhost:9092`.

```bash
java -jar services/transaction-incentive-api.jar          # Incentive API on port 8080
./mvnw spring-boot:run                                     # Midas Core on port 33400
```

If port 8080 is taken, start the Incentive API with `--server.port=18080` and run Midas with
`-Dmidas.incentive.url=http://localhost:18080/incentive`. Set `midas.incentive.enabled=false` to process
transactions without incentives.

## Tech

Java 17, Spring Boot 3.2, Spring Kafka, Spring Data JPA, Hibernate, H2, JUnit 5, Mockito, AssertJ,
Awaitility, embedded Kafka, GitHub Actions.
