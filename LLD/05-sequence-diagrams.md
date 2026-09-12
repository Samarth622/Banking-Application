# 05 — Sequence Diagrams

## 5.1 Account Creation Flow

```mermaid
sequenceDiagram
    actor User
    participant GW as API Gateway :8080
    participant AC as AccountController
    participant AS as AccountService
    participant AR as AccountRepository
    participant DB as MySQL (account_db)

    User->>GW: POST /api/v1/accounts
    Note over GW: Rate limit check (Redis)
    GW->>AC: Forward request

    AC->>AC: @Valid — validate request fields
    Note over AC: If validation fails → 400 Bad Request

    AC->>AS: createAccount(request)

    AS->>AR: existsByEmail(email)
    AR->>DB: SELECT COUNT(*) FROM accounts WHERE email = ?
    DB-->>AR: 0 or 1
    AR-->>AS: true/false

    alt Email already exists
        AS-->>AC: throw RuntimeException("Account already exists")
        AC-->>GW: 500 Internal Server Error
        GW-->>User: Error response
    end

    AS->>AS: generateAccountNumber()
    loop Until unique number found
        AS->>AS: SecureRandom → 12-digit number
        AS->>AR: existsByAccountNumber(number)
        AR->>DB: SELECT COUNT(*) FROM accounts WHERE account_number = ?
        DB-->>AR: 0 or 1
    end

    AS->>AS: Build Account entity
    Note over AS: Set type-based limit:<br/>SAVINGS → ₹100,000<br/>CURRENT → ₹500,000

    AS->>AR: save(account)
    AR->>DB: INSERT INTO accounts VALUES (...)
    DB-->>AR: Saved entity with generated UUID

    AS->>AS: mapToResponse(savedAccount)
    AS-->>AC: AccountResponse
    AC-->>GW: 201 Created + body
    GW-->>User: AccountResponse JSON
```

---

## 5.2 Money Transfer — Happy Path (Clean Transaction)

This is the most important flow in the system. The SAGA completes without any fraud detection triggers.

```mermaid
sequenceDiagram
    actor User
    participant GW as API Gateway :8080
    participant TC as TransactionController
    participant TS as TransactionService
    participant FC as Feign Client
    participant AS as Account Service :8081
    participant TR as TransactionRepo
    participant K as Kafka
    participant FDC as FraudDetectionConsumer
    participant FDS as FraudDetectionService
    participant FAC as Fraud Feign Client
    participant R as Redis
    participant TEC as TransactionEventConsumer
    participant AEC as AccountEventConsumer
    participant NS as NotificationService

    rect rgb(220, 240, 255)
        Note over User,NS: SAGA Step 1 — Initiate Transfer
        User->>GW: POST /api/v1/transactions/transfer
        GW->>TC: Forward
        TC->>TS: transfer(request)

        TS->>FC: deductBalance(sender, amount)
        FC->>AS: PUT /accounts/{sender}/deduct?amount=5000
        AS->>AS: Validate: ACTIVE + sufficient balance
        AS->>AS: balance = balance - 5000
        AS-->>FC: "Balance deducted Successfully"
        FC-->>TS: OK

        TS->>TR: save(transaction [status=PROCESSING])
        TR-->>TS: Saved with UUID

        TS->>K: PUBLISH transaction.initiated
        Note over K: Key: transactionId<br/>Value: TransactionInitiatedEvent

        TS-->>TC: TransactionResponse [PROCESSING]
        TC-->>GW: 201 Created
        GW-->>User: Response (status: PROCESSING)
    end

    rect rgb(255, 240, 220)
        Note over K,FDS: SAGA Step 2 — Fraud Check
        K->>FDC: CONSUME transaction.initiated
        FDC->>FDS: checkTransaction(payload)

        FDS->>FAC: getBalance(senderAccount)
        FAC->>AS: GET /accounts/{sender}/balance
        AS-->>FAC: 45000.00
        FAC-->>FDS: 45000.00

        FDS->>R: INCR fraud:velocity{sender}
        R-->>FDS: 1 (first txn in 60s)

        FDS->>R: GET fraud:avg_amount{sender}
        R-->>FDS: null (first txn)
        FDS->>R: SET fraud:avg_amount{sender} = "5000"

        FDS->>FDS: Balance check: 5000 < 45000 × 0.90 = 40500 ✓

        Note over FDS: All 3 checks PASSED → Clean
        FDS->>K: PUBLISH fraud.check.clean
    end

    rect rgb(220, 255, 220)
        Note over K,NS: SAGA Step 3 — Complete Transaction
        K->>TEC: CONSUME fraud.check.clean
        TEC->>TS: processCleanResult(transactionId)

        TS->>TR: findById(transactionId)
        TR-->>TS: Transaction [PROCESSING]

        TS->>TS: status → COMPLETED, set completedAt
        TS->>TR: save(transaction)

        TS->>K: PUBLISH transaction.completed
        Note over K: TransactionCompletedEvent

        K->>AEC: CONSUME transaction.completed
        AEC->>AS: creditBalance(receiver, 5000)
        Note over AS: receiver.balance += 5000

        K->>NS: CONSUME transaction.completed
        NS->>NS: Log DEBIT ALERT for sender
        NS->>NS: Log CREDIT ALERT for receiver
    end
```

---

## 5.3 Money Transfer — Suspicious Transaction (OTP Verification Required)

```mermaid
sequenceDiagram
    actor User
    participant TS as TransactionService
    participant FC as Feign Client
    participant AS as Account Service
    participant K as Kafka
    participant FDS as FraudDetectionService
    participant R as Redis
    participant TEC as TransactionEventConsumer
    participant TR as TransactionRepo
    participant NS as NotificationService

    Note over User,NS: Steps 1 is same as Happy Path (deduct + save + publish)
    Note over TS: Transaction saved as PROCESSING

    rect rgb(255, 230, 230)
        Note over K,R: SAGA Step 2 — Fraud Check FAILS
        K->>FDS: CONSUME transaction.initiated
        FDS->>R: INCR fraud:velocity{sender}
        R-->>FDS: 6 (exceeds limit of 5!)

        Note over FDS: Velocity check FAILED → Suspicious

        FDS->>K: PUBLISH verification.required
        Note over K: {transactionId, accountNumber,<br/>amount, reason: "Velocity limit exceeded"}
    end

    rect rgb(255, 245, 200)
        Note over K,NS: SAGA Step 2b — OTP Generation
        K->>TEC: CONSUME verification.required
        TEC->>TR: findById(transactionId)
        TR-->>TEC: Transaction [PROCESSING]

        TEC->>TEC: Generate 6-digit OTP (e.g., "482917")
        TEC->>R: SET verification:otp{txnId} "482917" EX 300
        Note over R: OTP stored with 5-minute TTL

        TEC->>TR: save(transaction [PENDING_VERIFICATION])

        TEC->>K: PUBLISH transaction.otp.generated
        Note over K: {transactionId, otp, reason, amount}

        K->>NS: CONSUME transaction.otp.generated
        NS->>NS: Log: "Your OTP is 482917, valid 5 min"
    end
```

---

## 5.4 OTP Verification — Correct OTP

```mermaid
sequenceDiagram
    actor User
    participant TC as TransactionController
    participant TS as TransactionService
    participant R as Redis
    participant TR as TransactionRepo
    participant K as Kafka
    participant AEC as AccountEventConsumer
    participant AS as AccountService
    participant NS as NotificationService

    User->>TC: POST /transactions/{txnId}/verify?otp=482917
    TC->>TS: verifyOTP(txnId, "482917")

    TS->>TR: findById(txnId)
    TR-->>TS: Transaction [PENDING_VERIFICATION]

    TS->>R: GET verification:otp{txnId}
    R-->>TS: "482917"

    TS->>TS: "482917" == "482917" ✓ Match!

    TS->>R: DEL verification:otp{txnId}

    TS->>TS: completeTransaction()
    TS->>TR: save(transaction [COMPLETED])

    TS->>K: PUBLISH transaction.completed

    K->>AEC: CONSUME transaction.completed
    AEC->>AS: creditBalance(receiver, amount)

    K->>NS: CONSUME transaction.completed
    NS->>NS: DEBIT ALERT + CREDIT ALERT

    TS-->>TC: TransactionResponse [COMPLETED]
    TC-->>User: 200 OK
```

---

## 5.5 OTP Verification — Wrong OTP (SAGA Compensation + Account Block)

```mermaid
sequenceDiagram
    actor User
    participant TC as TransactionController
    participant TS as TransactionService
    participant R as Redis
    participant TR as TransactionRepo
    participant FC as Feign Client
    participant AS as Account Service
    participant K as Kafka
    participant AEC as AccountEventConsumer
    participant NS as NotificationService

    User->>TC: POST /transactions/{txnId}/verify?otp=000000
    TC->>TS: verifyOTP(txnId, "000000")

    TS->>TR: findById(txnId)
    TR-->>TS: Transaction [PENDING_VERIFICATION]

    TS->>R: GET verification:otp{txnId}
    R-->>TS: "482917"

    TS->>TS: "482917" ≠ "000000" ✗ Mismatch!

    rect rgb(255, 200, 200)
        Note over TS,NS: SAGA COMPENSATION — Block + Refund
        TS->>R: DEL verification:otp{txnId}

        Note over TS: Step 1 — Publish fraud.detected
        TS->>K: PUBLISH fraud.detected
        Note over K: {accountNumber: sender, reason: "Wrong OTP"}

        K->>AEC: CONSUME fraud.detected
        AEC->>AS: blockAccount(senderAccountNumber)
        Note over AS: status → BLOCKED

        K->>NS: CONSUME fraud.detected
        NS->>NS: "Your account has been BLOCKED"

        Note over TS: Step 2 — Refund sender
        TS->>FC: creditBalance(sender, amount)
        FC->>AS: PUT /accounts/{sender}/credit?amount=5000
        AS->>AS: sender.balance += 5000
        AS-->>FC: OK

        TS->>TR: save(transaction [FLAGGED])
        Note over TR: failureReason = "Wrong OTP entered..."

        TS->>K: PUBLISH transaction.refunded
        K->>NS: CONSUME transaction.refunded
        NS->>NS: "Your ₹5000 has been refunded"
    end

    TS-->>TC: TransactionResponse [FLAGGED]
    TC-->>User: 200 OK
```

---

## 5.6 OTP Verification — OTP Expired (SAGA Compensation, No Block)

```mermaid
sequenceDiagram
    actor User
    participant TC as TransactionController
    participant TS as TransactionService
    participant R as Redis
    participant TR as TransactionRepo
    participant FC as Feign Client
    participant AS as Account Service
    participant K as Kafka
    participant NS as NotificationService

    Note over R: 5 minutes have passed — Redis auto-deleted the OTP key

    User->>TC: POST /transactions/{txnId}/verify?otp=482917
    TC->>TS: verifyOTP(txnId, "482917")

    TS->>TR: findById(txnId)
    TR-->>TS: Transaction [PENDING_VERIFICATION]

    TS->>R: GET verification:otp{txnId}
    R-->>TS: null (key expired!)

    rect rgb(255, 230, 200)
        Note over TS,NS: SAGA COMPENSATION — Refund Only (No Block)

        TS->>FC: creditBalance(sender, amount)
        FC->>AS: PUT /accounts/{sender}/credit?amount=5000
        AS->>AS: sender.balance += 5000

        TS->>TR: save(transaction [FLAGGED])
        Note over TR: failureReason = "OTP expired - transaction cancelled"

        TS->>K: PUBLISH transaction.refunded
        K->>NS: CONSUME transaction.refunded
        NS->>NS: "Your ₹5000 has been refunded"
    end

    TS-->>TC: TransactionResponse [FLAGGED]
    TC-->>User: 200 OK
```

---

## 5.7 Payment Flow — Razorpay Integration

```mermaid
sequenceDiagram
    actor User
    participant FE as Frontend
    participant GW as API Gateway
    participant PC as PaymentController
    participant PS as PaymentService
    participant RP as Razorpay API
    participant PR as PaymentRepo
    participant DB as MySQL
    participant K as Kafka
    participant NS as NotificationService

    rect rgb(220, 240, 255)
        Note over User,DB: Phase 1 — Order Creation
        User->>FE: Click "Pay ₹2500"
        FE->>GW: POST /api/v1/payments/create-order
        GW->>PC: Forward
        PC->>PS: createPaymentOrder(request)

        PS->>RP: Create Order (amount: 250000 paise)
        RP-->>PS: {id: "order_ABC123", status: "created"}

        PS->>PR: save(Payment [CREATED])
        PR->>DB: INSERT INTO payments

        PS-->>PC: PaymentOrderResponse
        PC-->>GW: 201 Created
        GW-->>FE: {razorpayOrderId, razorpayKeyId, amount}
    end

    rect rgb(255, 245, 200)
        Note over User,RP: Phase 2 — User Payment (Client-Side)
        FE->>FE: Open Razorpay Checkout widget
        User->>FE: Enter card details + pay
        FE->>RP: Process payment
        RP-->>FE: Payment result
    end

    rect rgb(220, 255, 220)
        Note over RP,NS: Phase 3 — Webhook Callback
        RP->>GW: POST /api/v1/payments/webhook
        Note over RP: {event: "payment.captured", payload: {...}}
        GW->>PC: Forward
        PC->>PS: handleWebhook(payload)

        PS->>PS: Extract orderId + paymentId from payload
        PS->>PR: findByRazorpayOrderId(orderId)
        PR->>DB: SELECT * FROM payments WHERE razorpay_order_id = ?
        DB-->>PR: Payment entity

        PS->>PS: Set razorpayPaymentId, status → COMPLETED
        PS->>PR: save(payment)

        PS->>K: PUBLISH payment.completed
        K->>NS: CONSUME payment.completed
        NS->>NS: "Payment of ₹2500 completed. Razorpay Id: pay_ABC123"
    end
```

---

## 5.8 Fraud Detection — Three Check Patterns

```mermaid
sequenceDiagram
    participant FDS as FraudDetectionService
    participant FC as Feign Client
    participant AS as Account Service
    participant R as Redis

    Note over FDS: Receive transaction.initiated event

    FDS->>FC: getBalance(senderAccount)
    FC->>AS: GET /accounts/{sender}/balance
    AS-->>FC: 50000.00
    FC-->>FDS: 50000.00

    rect rgb(255, 240, 240)
        Note over FDS,R: Pattern 1 — Velocity Check
        FDS->>R: INCR fraud:velocity{sender}
        R-->>FDS: count (e.g., 3)
        alt count == 1
            FDS->>R: EXPIRE fraud:velocity{sender} 60
        end
        Note over FDS: If count > 5 → SUSPICIOUS
    end

    rect rgb(240, 255, 240)
        Note over FDS,R: Pattern 2 — Amount Anomaly
        FDS->>R: GET fraud:avg_amount{sender}
        R-->>FDS: "2000" (running average)

        FDS->>FDS: threshold = 2000 × 5 = 10000
        FDS->>FDS: Is 5000 > 10000? → No

        FDS->>FDS: newAvg = (2000 + 5000) / 2 = 3500
        FDS->>R: SET fraud:avg_amount{sender} "3500"
    end

    rect rgb(240, 240, 255)
        Note over FDS: Pattern 3 — Balance Drain
        FDS->>FDS: maxAllowed = 50000 × 0.90 = 45000
        FDS->>FDS: Is 5000 > 45000? → No
    end

    Note over FDS: All checks passed → CLEAN
```

---

## 5.9 Complete Event Timeline — Suspicious Transaction End-to-End

A single timeline showing every event in chronological order for a suspicious transaction with correct OTP verification:

```
T+0s     User sends POST /transfer (₹5000, A → B)
T+0.1s   Account Service deducts ₹5000 from A (Feign)
T+0.2s   Transaction saved [PROCESSING]
T+0.3s   → Kafka: transaction.initiated

T+1s     Fraud Detection receives event
T+1.1s   Fraud Detection fetches A's balance (Feign)
T+1.2s   Velocity check: 6 transactions in 60s > limit 5 → SUSPICIOUS
T+1.3s   → Kafka: verification.required

T+2s     Transaction Service receives verification.required
T+2.1s   OTP "482917" generated
T+2.2s   Redis: SET verification:otp{txnId} "482917" EX 300
T+2.3s   Transaction saved [PENDING_VERIFICATION]
T+2.4s   → Kafka: transaction.otp.generated

T+3s     Notification Service logs OTP alert

T+60s    User submits POST /verify?otp=482917
T+60.1s  Redis: GET verification:otp{txnId} → "482917" (not expired)
T+60.2s  OTP matches → CORRECT
T+60.3s  Redis: DEL verification:otp{txnId}
T+60.4s  Transaction saved [COMPLETED]
T+60.5s  → Kafka: transaction.completed

T+61s    Account Service credits ₹5000 to B
T+61s    Notification Service logs DEBIT + CREDIT alerts

TOTAL TIME: ~61 seconds (mostly user think time)
```
