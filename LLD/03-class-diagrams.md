# 03 — Class Diagrams

## 3.1 Layered Architecture (Every Service Follows This)

Each microservice follows a strict **4-layer architecture**:

```mermaid
graph TB
    subgraph "Layer 1 — Controller (REST API)"
        C["@RestController<br/>Receives HTTP requests<br/>Validates input<br/>Returns ResponseEntity"]
    end

    subgraph "Layer 2 — Service (Business Logic)"
        S["@Service<br/>Business rules<br/>Orchestration<br/>Kafka publishing"]
    end

    subgraph "Layer 3 — Repository (Data Access)"
        R["JpaRepository<br/>Database queries<br/>CRUD operations"]
    end

    subgraph "Layer 4 — Entity / DTO (Data Models)"
        E["@Entity — JPA mapped<br/>DTO — API contracts<br/>Enums — Fixed values<br/>Events — Kafka payloads"]
    end

    C --> S
    S --> R
    S --> E
    R --> E
    C --> E
```

### Why This Layering?

| Layer | Responsibility | What It Must NOT Do |
|---|---|---|
| **Controller** | HTTP translation, validation, response formatting | Contain business logic, call repository directly |
| **Service** | Business rules, orchestration, event publishing | Know about HTTP status codes, handle JSON serialization |
| **Repository** | Data access, query execution | Contain business logic, make HTTP calls |
| **Entity/DTO** | Data structure definition | Contain behavior (pure data carriers) |

---

## 3.2 Account Service — Class Diagram

```mermaid
classDiagram
    class AccountController {
        -AccountService accountService
        +createAccount(CreateAccountRequest) ResponseEntity~AccountResponse~
        +getAccount(String accountNumber) ResponseEntity~AccountResponse~
        +getBalance(String accountNumber) ResponseEntity~BigDecimal~
        +blockAccount(String accountNumber) ResponseEntity~String~
        +deductBalance(String accountNumber, BigDecimal amount) ResponseEntity~String~
        +creditBalance(String accountNumber, BigDecimal amount) ResponseEntity~String~
    }

    class AccountService {
        -AccountRepository accountRepository
        -SecureRandom secureRandom$
        +createAccount(CreateAccountRequest) AccountResponse
        +getAccount(String accountNumber) AccountResponse
        +getBalance(String accountNumber) BigDecimal
        +blockAccount(String accountNumber) void
        +deductBalance(String accountNumber, BigDecimal amount) void
        +creditBalance(String accountNumber, BigDecimal amount) void
        -generateAccountNumber() String
        -mapToResponse(Account) AccountResponse
    }

    class AccountEventConsumer {
        -AccountService accountService
        +consumeTransactionCompleted(Map payload) void
        +consumeFraudDetected(Map payload) void
    }

    class AccountRepository {
        <<interface>>
        +existsByEmail(String email) boolean
        +existsByAccountNumber(String accountNumber) boolean
        +findByAccountNumber(String accountNumber) Optional~Account~
    }

    class Account {
        <<Entity>>
        -String id
        -String accountNumber
        -String accountHolderName
        -String email
        -String phone
        -AccountType accountType
        -AccountStatus status
        -BigDecimal balance
        -BigDecimal dailyTransactionLimit
        -LocalDateTime createdAt
        -LocalDateTime updatedAt
    }

    class AccountType {
        <<Enum>>
        SAVINGS
        CURRENT
        FIXED_DEPOSIT
    }

    class AccountStatus {
        <<Enum>>
        ACTIVE
        BLOCKED
        CLOSED
    }

    class CreateAccountRequest {
        <<DTO>>
        -String accountHolderName
        -String email
        -String phone
        -AccountType accountType
        -BigDecimal initialDeposit
    }

    class AccountResponse {
        <<DTO>>
        -String id
        -String accountNumber
        -String accountHolderName
        -String email
        -String phone
        -AccountType accountType
        -AccountStatus status
        -BigDecimal balance
        -BigDecimal dailyTransactionLimit
        -LocalDateTime createdAt
    }

    AccountController --> AccountService : uses
    AccountEventConsumer --> AccountService : uses
    AccountService --> AccountRepository : uses
    AccountService ..> Account : creates/reads
    AccountService ..> AccountResponse : returns
    AccountService ..> CreateAccountRequest : accepts
    AccountRepository --> Account : manages
    Account --> AccountType : has
    Account --> AccountStatus : has
    CreateAccountRequest --> AccountType : has
    AccountResponse --> AccountType : has
    AccountResponse --> AccountStatus : has
```

### Key Design Decisions

| Decision | Reasoning |
|---|---|
| `AccountEventConsumer` is separate from `AccountService` | **Single Responsibility** — consumer handles Kafka deserialization and error handling; service handles business logic |
| `AccountRepository` is an interface | **Repository Pattern** — Spring Data JPA generates implementation at runtime |
| `CreateAccountRequest` ≠ `AccountResponse` | **Separate input/output DTOs** — input has `initialDeposit`, output has `balance`, `id`, `createdAt` |
| `mapToResponse()` is private | **Encapsulation** — mapping logic is an internal implementation detail |
| `generateAccountNumber()` uses `SecureRandom` | **Security** — `Math.random()` is predictable; `SecureRandom` is cryptographically secure |

---

## 3.3 Transaction Service — Class Diagram

```mermaid
classDiagram
    class TransactionController {
        -TransactionService transactionService
        +transfer(TransferRequest) ResponseEntity~TransactionResponse~
        +getTransaction(String transactionId) ResponseEntity~TransactionResponse~
        +getTransactionHistory(String accountNumber) ResponseEntity~List~
        +verifyOTP(String transactionId, String otp) ResponseEntity~TransactionResponse~
    }

    class TransactionService {
        -TransactionRepository transactionRepository
        -AccountServiceClient accountServiceClient
        -KafkaTemplate kafkaTemplate
        -RedisTemplate redisTemplate
        +transfer(TransferRequest) TransactionResponse
        +getTransaction(String transactionId) TransactionResponse
        +getTransactionHistory(String accountNumber) List~TransactionResponse~
        +verifyOTP(String transactionId, String otp) TransactionResponse
        +processCleanResult(String transactionId) void
        -compensateTransaction(Transaction, String reason) void
        -blockingAccountAndCompensate(Transaction, String reason) void
        -completeTransaction(Transaction) void
        -mapToResponse(Transaction) TransactionResponse
    }

    class TransactionEventConsumer {
        -TransactionRepository transactionRepository
        -RedisTemplate redisTemplate
        -TransactionService transactionService
        -KafkaTemplate kafkaTemplate
        +consumeVerificationRequired(Map payload) void
        +consumeFraudCheckCleanResult(Map payload) void
    }

    class AccountServiceClient {
        <<Feign Interface>>
        +deductBalance(String accountNumber, BigDecimal amount) String
        +creditBalance(String accountNumber, BigDecimal amount) String
    }

    class TransactionRepository {
        <<interface>>
        +findBySenderAccountNumberOrderByCreatedAtDesc(String) List~Transaction~
    }

    class Transaction {
        <<Entity>>
        -String id
        -String senderAccountNumber
        -String receiverAccountNumber
        -BigDecimal amount
        -TransactionType type
        -TransactionStatus status
        -String description
        -String failureReason
        -String referenceNumber
        -LocalDateTime createdAt
        -LocalDateTime completedAt
    }

    class TransactionStatus {
        <<Enum>>
        PENDING
        PROCESSING
        PENDING_VERIFICATION
        COMPLETED
        FAILED
        FLAGGED
    }

    class TransactionType {
        <<Enum>>
        DEPOSIT
        WITHDRAWAL
        PAYMENT
        TRANSFER
    }

    class TransactionInitiatedEvent {
        <<Event DTO>>
        -String transactionId
        -String senderAccountNumber
        -String receiverAccountNumber
        -BigDecimal amount
        -String description
    }

    class TransactionCompletedEvent {
        <<Event DTO>>
        -String transactionId
        -String senderAccountNumber
        -String receiverAccountNumber
        -BigDecimal amount
        -String description
    }

    class TransferRequest {
        <<DTO>>
        -String senderAccountNumber
        -String receiverAccountNumber
        -BigDecimal amount
        -String description
    }

    class TransactionResponse {
        <<DTO>>
        -String id
        -String senderAccountNumber
        -String receiverAccountNumber
        -BigDecimal amount
        -TransactionType type
        -TransactionStatus status
        -String description
        -String failureReason
        -String referenceNumber
        -LocalDateTime createdAt
        -LocalDateTime completedAt
    }

    TransactionController --> TransactionService
    TransactionService --> TransactionRepository
    TransactionService --> AccountServiceClient
    TransactionService --> KafkaTemplate
    TransactionService --> RedisTemplate
    TransactionService ..> Transaction
    TransactionService ..> TransactionInitiatedEvent : publishes
    TransactionService ..> TransactionCompletedEvent : publishes
    TransactionEventConsumer --> TransactionRepository
    TransactionEventConsumer --> TransactionService
    TransactionEventConsumer --> RedisTemplate
    TransactionEventConsumer --> KafkaTemplate
    Transaction --> TransactionStatus
    Transaction --> TransactionType

    class KafkaTemplate {
        <<Spring>>
        +send(String topic, String key, Object value) void
    }

    class RedisTemplate {
        <<Spring>>
        +opsForValue() ValueOperations
    }
```

### Key Design Decisions

| Decision | Reasoning |
|---|---|
| 4 dependencies in `TransactionService` | SAGA orchestrator needs: DB (state), Feign (sync calls), Kafka (async events), Redis (OTP) |
| `compensateTransaction()` is private | Compensation is internal SAGA logic — never called externally |
| `TransactionEventConsumer` is separate | Separates event consumption (infrastructure) from business logic (service) |
| Event classes mirror `Transaction` entity | Events carry only the data consumers need — no internal fields like `failureReason` |
| `processCleanResult()` is public | Called by `TransactionEventConsumer` — must be accessible but not via HTTP |

---

## 3.4 Payment Service — Class Diagram

```mermaid
classDiagram
    class PaymentController {
        -PaymentService paymentService
        +createPaymentOrder(CreatePaymentRequest) ResponseEntity~PaymentOrderResponse~
        +handleWebhook(Map payload) ResponseEntity~String~
    }

    class PaymentService {
        -PaymentRepository paymentRepository
        -KafkaTemplate kafkaTemplate
        -String keyID
        -String keySecret
        +createPaymentOrder(CreatePaymentRequest) PaymentOrderResponse
        +handleWebhook(Map payload) void
        -handlePaymentSuccess(Map payload) void
        -handlePaymentFailed(Map payload) void
        -extractPaymentData(Map payload) Map
    }

    class CorsConfig {
        <<Configuration>>
        +corsConfigurer() WebMvcConfigurer
    }

    class PaymentRepository {
        <<interface>>
        +findByRazorpayOrderId(String orderId) Optional~Payment~
    }

    class Payment {
        <<Entity>>
        -String id
        -String razorpayOrderId
        -String razorpayPaymentId
        -String accountNumber
        -BigDecimal amount
        -String currency
        -PaymentStatus status
        -String description
        -String failureReason
        -LocalDateTime createdAt
        -LocalDateTime updatedAt
    }

    class PaymentStatus {
        <<Enum>>
        CREATED
        PENDING
        COMPLETED
        FAILED
        REFUNDED
    }

    class CreatePaymentRequest {
        <<DTO>>
        -String accountNumber
        -BigDecimal amount
        -String description
    }

    class PaymentOrderResponse {
        <<DTO>>
        -String paymentId
        -String razorpayOrderId
        -BigDecimal amount
        -String currency
        -String status
        -String razorpayKeyId
    }

    PaymentController --> PaymentService
    PaymentService --> PaymentRepository
    PaymentService --> KafkaTemplate
    PaymentService ..> RazorpayClient : creates
    PaymentRepository --> Payment
    Payment --> PaymentStatus

    class RazorpayClient {
        <<External Library>>
        +orders Orders
    }
```

---

## 3.5 Fraud Detection Service — Class Diagram

```mermaid
classDiagram
    class FraudDetectionEventConsumer {
        -FraudDetectionService fraudDetectionService
        +consumeTransactionInitiated(Map payload) void
    }

    class FraudDetectionService {
        -AccountServiceClient accountServiceClient
        -KafkaTemplate kafkaTemplate
        -RedisTemplate redisTemplate
        -int maxTransactionsPerMinute
        -double suspiciousAmountMultiplier
        -double maxBalancePercentage
        +checkTransaction(Map payload) void
        -performFraudChecks(String accountNumber, BigDecimal amount, BigDecimal balance) FraudCheckResult
        -isVelocityExceeded(String accountNumber) boolean
        -isAmountSuspicious(String accountNumber, BigDecimal amount) boolean
        -isBalanceCheckFailed(BigDecimal balance, BigDecimal amount) boolean
    }

    class AccountServiceClient {
        <<Feign Interface>>
        +getBalance(String accountNumber) BigDecimal
    }

    class FraudCheckResult {
        <<Model>>
        -Boolean fraud
        -String reason
    }

    FraudDetectionEventConsumer --> FraudDetectionService
    FraudDetectionService --> AccountServiceClient
    FraudDetectionService --> KafkaTemplate
    FraudDetectionService --> RedisTemplate
    FraudDetectionService ..> FraudCheckResult : produces
```

### Key Design Decisions

| Decision | Reasoning |
|---|---|
| No REST controller | Fraud detection is event-driven only — triggered by Kafka, not HTTP |
| No JPA / Database | Stateless service — uses Redis for transient counters and Feign for lookups |
| Configurable thresholds via `@Value` | Fraud rules can be tuned via `application.yaml` without code changes |
| `FraudCheckResult` is a simple POJO | Not an entity — never persisted. Just a method return type. |
| 3 independent checks | Each check is a separate private method — easy to add/remove rules |

---

## 3.6 Notification Service — Class Diagram

```mermaid
classDiagram
    class NotificationService {
        +consumeOTPGenerated(Map payload) void
        +consumeTransactionCompleted(Map payload) void
        +consumeFraudDetected(Map payload) void
        +consumeTransactionRefunded(Map payload) void
        +consumePaymentCompleted(Map payload) void
        +consumePaymentFailed(Map payload) void
        -sendAlert(String accountNumber, String subject, String message) void
    }
```

### Key Design Decision

The Notification Service is the **simplest** in the system — intentionally. It has:
- No controller (no REST API)
- No repository (no database)
- No entity (no persistence)
- No Feign client (no sync calls)

It is a **pure Kafka consumer** that transforms events into human-readable alerts. Currently logs to console; designed to be replaced with email/SMS/push notification providers.

---

## 3.7 API Gateway — Class Diagram

```mermaid
classDiagram
    class ApiGatewayApplication {
        +main(String[] args) void
    }

    class RateLimiterConfig {
        <<Configuration>>
        +keyResolver() KeyResolver
    }

    class KeyResolver {
        <<Functional Interface>>
        +resolve(ServerWebExchange) Mono~String~
    }

    ApiGatewayApplication ..> RateLimiterConfig
    RateLimiterConfig ..> KeyResolver : creates
```

### Key Design Decision

The gateway is **configuration-driven**, not code-driven. Routes are defined in `application.yaml`, not in Java. The only Java code is the `RateLimiterConfig` which tells Spring Cloud Gateway to rate-limit by client IP address.

---

## 3.8 Cross-Service Dependency Map

```mermaid
graph LR
    subgraph "Sync Dependencies (Feign)"
        TS -->|deductBalance, creditBalance| AS
        FD -->|getBalance| AS
    end

    subgraph "Async Dependencies (Kafka)"
        TS -.->|transaction.initiated| FD
        FD -.->|verification.required| TS
        FD -.->|fraud.check.clean| TS
        TS -.->|transaction.completed| AS
        TS -.->|transaction.completed| NS
        TS -.->|fraud.detected| AS
        TS -.->|fraud.detected| NS
        TS -.->|transaction.refunded| NS
        TS -.->|transaction.otp.generated| NS
        PS -.->|payment.completed| NS
        PS -.->|payment.failed| NS
    end

    AS["Account Service"]
    TS["Transaction Service"]
    PS["Payment Service"]
    FD["Fraud Detection"]
    NS["Notification"]
```

**Key Insight:** Account Service is the most depended-upon service. If it goes down, transfers and fraud checks fail. This makes it the most critical service to keep highly available.
