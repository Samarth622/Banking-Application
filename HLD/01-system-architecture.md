# 01 — System Architecture

## 1.1 Problem Statement

Design a **banking system** that allows users to:
- Create and manage bank accounts
- Transfer money between accounts with real-time fraud detection
- Process payments through an external payment gateway
- Receive notifications for all financial activities

### Functional Requirements

| # | Requirement | Priority |
|---|---|---|
| FR-1 | Users can create accounts (Savings, Current, Fixed Deposit) | P0 |
| FR-2 | Users can check account balance and details | P0 |
| FR-3 | Users can transfer money between accounts | P0 |
| FR-4 | System detects suspicious transactions in real time | P0 |
| FR-5 | Suspicious transactions require OTP verification | P0 |
| FR-6 | Fraudulent activity blocks the account automatically | P1 |
| FR-7 | Users receive notifications for all transaction events | P1 |
| FR-8 | Users can make payments through Razorpay | P1 |
| FR-9 | SAGA compensation refunds money when transactions fail | P0 |

### Non-Functional Requirements

| # | Requirement | Target |
|---|---|---|
| NFR-1 | **Availability** | 99.9% uptime (8.76 hours downtime/year) |
| NFR-2 | **Latency** | < 500ms for transfer initiation, < 2s for end-to-end completion |
| NFR-3 | **Throughput** | Support 1,000 concurrent transfers/second at peak |
| NFR-4 | **Consistency** | Eventual consistency with SAGA guarantees — no money lost |
| NFR-5 | **Security** | Rate limiting, fraud detection, account blocking |
| NFR-6 | **Scalability** | Each service scales independently based on load |
| NFR-7 | **Fault Tolerance** | Single service failure doesn't cascade to others |

---

## 1.2 Architecture Style Decision

### Why Microservices?

```mermaid
graph TB
    subgraph "Option 1: Monolith"
        M["Single Application<br/>━━━━━━━━━━━━━━━<br/>+ Simple deployment<br/>+ Easy debugging<br/>+ No network latency<br/>━━━━━━━━━━━━━━━<br/>- Single point of failure<br/>- Scales as one unit<br/>- Slow CI/CD<br/>- Tight coupling"]
    end

    subgraph "Option 2: Microservices ✓"
        MS["6 Independent Services<br/>━━━━━━━━━━━━━━━<br/>+ Independent scaling<br/>+ Fault isolation<br/>+ Tech flexibility<br/>+ Team autonomy<br/>━━━━━━━━━━━━━━━<br/>- Network complexity<br/>- Distributed data<br/>- Operational overhead<br/>- Eventually consistent"]
    end

    subgraph "Option 3: Serverless"
        SL["Cloud Functions<br/>━━━━━━━━━━━━━━━<br/>+ Zero ops<br/>+ Pay per use<br/>+ Auto-scaling<br/>━━━━━━━━━━━━━━━<br/>- Cold starts<br/>- Vendor lock-in<br/>- Hard to debug<br/>- Stateless only"]
    end
```

**Decision: Microservices** — Banking requires fine-grained control over data consistency, independent scaling of transaction-heavy services, and fault isolation to prevent cascade failures.

---

## 1.3 High-Level Architecture Diagram

```mermaid
graph TB
    subgraph "Client Layer"
        WEB["Web Application"]
        MOB["Mobile App"]
        API["Third-Party APIs"]
    end

    subgraph "Edge Layer"
        LB["Load Balancer<br/>(Future: Nginx / AWS ALB)"]
        GW["API Gateway :8080<br/>Spring Cloud Gateway<br/>━━━━━━━━━━━━━━<br/>• Path-based routing<br/>• Rate limiting (Redis)<br/>• Health checks"]
    end

    subgraph "Service Layer"
        AS["Account Service :8081<br/>━━━━━━━━━━━━━━<br/>• Account CRUD<br/>• Balance management<br/>• Account blocking"]
        TS["Transaction Service :8082<br/>━━━━━━━━━━━━━━<br/>• Transfer orchestration<br/>• SAGA coordination<br/>• OTP verification"]
        PS["Payment Service :8083<br/>━━━━━━━━━━━━━━<br/>• Razorpay integration<br/>• Payment lifecycle<br/>• Webhook handling"]
        FD["Fraud Detection :8084<br/>━━━━━━━━━━━━━━<br/>• Velocity check<br/>• Amount anomaly<br/>• Balance drain check"]
        NS["Notification Service :8085<br/>━━━━━━━━━━━━━━<br/>• Alert dispatch<br/>• Multi-channel notify"]
    end

    subgraph "Data Layer"
        MySQL["MySQL 8.0<br/>━━━━━━━━━━━━━━<br/>• account_db<br/>• transaction_db"]
        Redis["Redis<br/>━━━━━━━━━━━━━━<br/>• OTP storage<br/>• Fraud counters<br/>• Rate limiter state"]
        Kafka["Apache Kafka<br/>━━━━━━━━━━━━━━<br/>• 9 event topics<br/>• Async messaging<br/>• Event backbone"]
    end

    subgraph "External Services"
        RP["Razorpay Payment Gateway"]
        SMTP["Email Service (Future)"]
        SMS["SMS Gateway (Future)"]
    end

    WEB & MOB & API --> LB --> GW
    GW --> AS & TS & PS

    TS -->|"Feign (sync)"| AS
    FD -->|"Feign (sync)"| AS

    TS -.->|"Kafka (async)"| Kafka
    FD -.->|"Kafka (async)"| Kafka
    PS -.->|"Kafka (async)"| Kafka
    Kafka -.-> AS & TS & NS & FD

    AS & TS --> MySQL
    PS --> MySQL
    GW & TS & FD --> Redis
    Kafka --> ZK["Zookeeper"]
    PS --> RP
    NS -.-> SMTP & SMS
```

---

## 1.4 Service Responsibilities Matrix

| Service | Owns Data? | Exposes REST API? | Publishes Events? | Consumes Events? | External Dependencies? |
|---|---|---|---|---|---|
| **API Gateway** | No (Redis for state) | Yes (proxy) | No | No | Redis |
| **Account Service** | Yes (`account_db.accounts`) | Yes (6 endpoints) | No | Yes (2 topics) | MySQL |
| **Transaction Service** | Yes (`transaction_db.transactions`) | Yes (4 endpoints) | Yes (5 topics) | Yes (2 topics) | MySQL, Redis, Kafka |
| **Payment Service** | Yes (`account_db.payments`) | Yes (2 endpoints) | Yes (2 topics) | No | MySQL, Kafka, Razorpay |
| **Fraud Detection** | No (Redis counters) | No | Yes (2 topics) | Yes (1 topic) | Redis, Kafka |
| **Notification** | No | No | No | Yes (6 topics) | Kafka |

---

## 1.5 Communication Patterns

The system uses two communication patterns — each chosen deliberately for specific interactions.

### Synchronous (Feign HTTP — Request/Response)

```mermaid
graph LR
    subgraph "When caller MUST WAIT for result"
        TS["Transaction Service"] -->|"PUT /deduct"| AS["Account Service"]
        TS -->|"PUT /credit"| AS
        FD["Fraud Detection"] -->|"GET /balance"| AS
    end
```

**Used when:**
- Transfer cannot proceed without confirmed deduction
- Fraud check needs current balance to decide
- SAGA compensation must confirm refund

**Trade-off:** Creates temporal coupling — if Account Service is slow or down, the caller is blocked.

### Asynchronous (Kafka Events — Fire & React)

```mermaid
graph LR
    subgraph "When caller proceeds WITHOUT waiting"
        TS["Transaction Service"] -.->|"transaction.initiated"| K["Kafka"] -.-> FD["Fraud Detection"]
        TS -.->|"transaction.completed"| K2["Kafka"] -.-> AS["Account Service"]
        K2 -.-> NS["Notification"]
    end
```

**Used when:**
- Fraud checking takes variable time
- Notifications are fire-and-forget
- Receiver crediting happens after the sender gets confirmation
- Multiple services need to react to the same event

**Trade-off:** Eventual consistency — receiver balance update has a delay.

---

## 1.6 Request Lifecycle — Complete Transfer Journey

```mermaid
graph TD
    A["User: POST /transfer<br/>₹5,000 from A → B"] --> B["API Gateway :8080"]

    B --> C{"Rate Limit<br/>Check (Redis)"}
    C -->|"Exceeded"| D["429 Too Many Requests"]
    C -->|"OK"| E["Route to Transaction Service :8082"]

    E --> F["Validate request body"]
    F --> G["Feign: Deduct ₹5,000 from A<br/>(Account Service :8081)"]

    G --> H{"Deduction<br/>Successful?"}
    H -->|"No (insufficient balance)"| I["500 Error to User"]
    H -->|"Yes"| J["Save transaction: PROCESSING"]

    J --> K["Kafka: transaction.initiated"]
    K --> L["Return 201 to User<br/>(status: PROCESSING)"]

    K --> M["Fraud Detection :8084<br/>Consumes event"]

    M --> N{"Fraud Check<br/>Result?"}

    N -->|"Clean"| O["Kafka: fraud.check.clean"]
    O --> P["Transaction Service:<br/>Status → COMPLETED"]
    P --> Q["Kafka: transaction.completed"]
    Q --> R["Account Service:<br/>Credit ₹5,000 to B"]
    Q --> S["Notification Service:<br/>Send alerts"]

    N -->|"Suspicious"| T["Kafka: verification.required"]
    T --> U["Transaction Service:<br/>Generate OTP<br/>Store in Redis (5 min TTL)"]
    U --> V["Kafka: transaction.otp.generated"]
    V --> W["Notification Service:<br/>Send OTP to user"]
    W --> X["User: POST /verify?otp=XXXXXX"]

    X --> Y{"OTP Valid?"}
    Y -->|"Correct"| P
    Y -->|"Wrong"| Z["Block account + Refund<br/>(SAGA Compensation)"]
    Y -->|"Expired"| AA["Refund only<br/>(SAGA Compensation)"]
```

---

## 1.7 Service Interaction Summary

### Total Interactions: 15

| # | From | To | Type | Protocol | Purpose |
|---|---|---|---|---|---|
| 1 | Client | API Gateway | Sync | HTTP | All external requests |
| 2 | Gateway | Account Service | Sync | HTTP (proxy) | Account operations |
| 3 | Gateway | Transaction Service | Sync | HTTP (proxy) | Transfer operations |
| 4 | Gateway | Payment Service | Sync | HTTP (proxy) | Payment operations |
| 5 | Transaction → Account | Sync | Feign HTTP | Deduct balance |
| 6 | Transaction → Account | Sync | Feign HTTP | Credit balance (compensation) |
| 7 | Fraud Detection → Account | Sync | Feign HTTP | Get balance |
| 8 | Transaction → Fraud Detection | Async | Kafka | Transaction initiated |
| 9 | Fraud Detection → Transaction | Async | Kafka | Verification required |
| 10 | Fraud Detection → Transaction | Async | Kafka | Fraud check clean |
| 11 | Transaction → Account | Async | Kafka | Transaction completed |
| 12 | Transaction → Notification | Async | Kafka | Multiple event types |
| 13 | Transaction → Account + Notification | Async | Kafka | Fraud detected |
| 14 | Payment → Notification | Async | Kafka | Payment completed/failed |
| 15 | Razorpay → Payment Service | Async | HTTP Webhook | Payment result callback |

---

## 1.8 Bounded Contexts (Domain-Driven Design)

Each service represents a **bounded context** — a self-contained domain with its own language, data, and rules.

```mermaid
graph TB
    subgraph BC1["Bounded Context: Account Management"]
        A1["Account"]
        A2["Balance"]
        A3["Account Status"]
        A4["Account Type"]
        A5["Daily Limit"]
    end

    subgraph BC2["Bounded Context: Transaction Processing"]
        B1["Transaction"]
        B2["Transfer"]
        B3["SAGA Step"]
        B4["OTP"]
        B5["Compensation"]
    end

    subgraph BC3["Bounded Context: Risk Assessment"]
        C1["Fraud Check"]
        C2["Velocity"]
        C3["Amount Anomaly"]
        C4["Balance Drain"]
        C5["Risk Score"]
    end

    subgraph BC4["Bounded Context: Payment Processing"]
        D1["Payment Order"]
        D2["Razorpay Integration"]
        D3["Webhook"]
        D4["Payment Status"]
    end

    subgraph BC5["Bounded Context: Notification"]
        E1["Alert"]
        E2["Channel"]
        E3["Template"]
    end

    BC2 -->|"Account Number<br/>(shared identifier)"| BC1
    BC3 -->|"Account Number"| BC1
    BC2 -->|"Transaction Event"| BC3
    BC2 -->|"Alert Event"| BC5
    BC4 -->|"Alert Event"| BC5
```

**Key insight:** Services communicate through **shared identifiers** (like `accountNumber`) not through shared data. Each bounded context defines its own meaning for the concepts it owns.

For example, "Account" in the Account Service means a full entity with balance, status, and limits. In Transaction Service, "account" is just a `senderAccountNumber` string — a reference, not the entity itself.
