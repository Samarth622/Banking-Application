# 05 — Security Design

## 5.1 Threat Modeling: STRIDE Analysis

A banking system is a high-value target for sophisticated cybercriminals, internal rogue actors, and automated bot networks. We systematically evaluate the system using the **STRIDE** methodology:

```mermaid
graph TD
    subgraph "STRIDE Threat Categories in Banking"
        S["Spoofing<br/>Attacker impersonates account owner or gateway"]
        T["Tampering<br/>Modifying transfer amounts or transaction statuses in transit/DB"]
        R["Repudiation<br/>User denies making a transfer or admin denies modifying balance"]
        I["Information Disclosure<br/>Leaking account balances, PII, or internal network topology"]
        D["Denial of Service<br/>Flooding Gateway with transfer requests to trigger downtime"]
        E["Elevation of Privilege<br/>Customer exploiting an endpoint to execute teller/admin actions"]
    end
```

| Threat | Vulnerability Vector | Defense Implemented in Architecture |
|---|---|---|
| **Spoofing** | Stolen credentials, forged session tokens | OIDC/OAuth 2.0 with RS256 signed JWTs; Multi-Factor Auth (SMS/Email OTP for transfers > $100). |
| **Tampering** | Man-in-the-Middle (MitM) or DB row alteration | End-to-End TLS 1.3; HMAC payload signatures on webhooks; DB row-level cryptographic hash checks. |
| **Repudiation** | Customer claims: "I never sent this money" | Immutable Kafka audit topic + write-once audit log table containing IP, User-Agent, timestamp, and device fingerprint. |
| **Information Disclosure** | SQL injection, leaked stack traces, open logs | Parameterized queries via Spring Data JPA; PII field-level AES-256 encryption; centralized log scrubbing (masks card/account numbers). |
| **Denial of Service** | Botnet hammering transfer endpoints | Cloudflare Edge DDoS mitigation; Spring Cloud Gateway Redis Token Bucket rate limiting; strict connection limits. |
| **Elevation of Privilege** | Normal user calling `/api/accounts/block` | Role-Based Access Control (RBAC) enforced at Gateway and verified inside each microservice via `@PreAuthorize`. |

---

## 5.2 Authentication & Authorization Architecture

The system implements a modern **Zero-Trust Identity Architecture** using OpenID Connect (OIDC) and OAuth 2.0 with stateless JSON Web Tokens (JWT).

```mermaid
sequenceDiagram
    autonumber
    participant C as Client (Web / Mobile)
    participant GW as API Gateway
    participant Auth as Identity Provider (Keycloak / Auth0)
    participant TS as Transaction Service
    participant R as Redis (Token Blacklist)

    C->>Auth: 1. Login with Credentials + Device MFA
    Auth-->>C: 2. Return Access Token (JWT, 15m) + Refresh Token (7d)
    
    C->>GW: 3. POST /api/transactions/transfer + Bearer JWT
    GW->>R: 4. Check if Token ID (JTI) is Revoked / Blacklisted
    R-->>GW: Token Active (Not Blacklisted)
    GW->>GW: 5. Verify RS256 Signature using IdP Public Key
    GW->>GW: 6. Extract Claims (userId, roles, permissions)
    GW->>TS: 7. Forward Request + X-User-Id, X-User-Role Headers
    TS->>TS: 8. Execute @PreAuthorize("hasRole('CUSTOMER')")
    TS-->>C: 9. HTTP 200 Success
```

### JWT Token Structure & Security
- **Algorithm**: Asymmetric `RS256` (RSA Signature with SHA-256). Private key stays strictly in Auth Server; public keys distributed via JWKS endpoint (`/.well-known/jwks.json`).
- **Lifespan**:
  - Access Token: Short-lived (**15 minutes**) to limit stolen token blast radius.
  - Refresh Token: Long-lived (**7 days**), stored in secure `HttpOnly`, `SameSite=Strict` cookies.
- **Instant Revocation (Blacklist)**:
  - If an account is frozen or user logs out, the JWT unique identifier (`jti`) is written to Redis with a TTL equal to the token's remaining lifespan. The Gateway checks this cache before routing.

---

## 5.3 Edge & API Gateway Security

```mermaid
graph TB
    Internet["Public Internet"] --> WAF["AWS WAF / Cloudflare<br/>• DDoS Protection<br/>• SQLi / XSS Shield<br/>• Geo-IP Filtering"]
    WAF --> TLS["TLS 1.3 Termination<br/>HSTS Enabled, Strong Ciphers only"]
    TLS --> GW["API Gateway (:8080)<br/>• Redis Rate Limiter<br/>• JWT Verification<br/>• CORS Enforcement<br/>• Request Sanitization"]
    GW --> Services["Internal Banking Network (VPC)<br/>Private Subnets Only"]
```

### Gateway Security Controls
1. **Strict Transport Security (HSTS)**:
   ```http
   Strict-Transport-Security: max-age=63072000; includeSubDomains; preload
   ```
2. **Cross-Origin Resource Sharing (CORS)**:
   - Whitelist restricted solely to trusted bank domains (e.g., `https://banking.example.com`).
   - Wildcards (`*`) strictly rejected.
3. **Webhook Security (Payment Gateway)**:
   - Razorpay webhook callbacks are authenticated by calculating an HMAC-SHA256 signature using a shared secret key and matching it against the `X-Razorpay-Signature` header.

---

## 5.4 Data Protection & Cryptography

Banking data is classified into 3 sensitivity tiers with corresponding encryption policies:

```mermaid
graph LR
    subgraph "Tier 1: Public / Operational"
        T1["Bank branch codes, IFSC, Service status<br/>Policy: TLS 1.3 in transit"]
    end

    subgraph "Tier 2: Confidential Banking Data"
        T2["Account numbers, Balances, Transaction amounts<br/>Policy: AES-256 at rest (Transparent Data Encryption)"]
    end

    subgraph "Tier 3: Highly Sensitive PII / Secret"
        T3["National ID (SSN/Aadhaar), PINs, Passwords, Card CVV<br/>Policy: Application Field-Level Encryption + Argon2id Hashing"]
    end
```

### Cryptographic Standards

| Layer | Technique | Standard | Key Management |
|---|---|---|---|
| **In-Transit** | Network Transport Layer Security | TLS 1.3 exclusively (disable TLS 1.0, 1.1, 1.2) | AWS Certificate Manager / Let's Encrypt auto-renewed |
| **At-Rest (DB & Kafka)** | Transparent Storage Encryption | AES-256-GCM | AWS KMS / HashiCorp Vault with annual key rotation |
| **Field-Level (Application)** | Sensitive PII Column Encryption | AES-256-CBC with per-column Initialization Vector (IV) | HashiCorp Vault Transit Engine |
| **Passwords & PINs** | One-Way Salted Password Hash | Argon2id (Memory: 64MB, Iterations: 3, Parallelism: 4) | Salts generated with CSPRNG |

---

## 5.5 Zero-Trust Service-to-Service Security (East-West Traffic)

In traditional systems, the internal network is treated as a "trusted zone". If an attacker breaches the perimeter, they can move laterally unimpeded. This architecture adopts **Zero-Trust**:

```mermaid
graph TB
    subgraph "Service Mesh (Istio / Envoy Proxy)"
        subgraph "Transaction Pod"
            TS["Transaction App"]
            EP1["Envoy Sidecar"]
        end

        subgraph "Account Pod"
            AS["Account App"]
            EP2["Envoy Sidecar"]
        end
    end

    TS -->|"Plain HTTP (Localhost)"| EP1
    EP1 -->|"Mutual TLS (mTLS) with SPIFFE X.509 Certs"| EP2
    EP2 -->|"Plain HTTP (Localhost)"| AS
```

### 1. Mutual TLS (mTLS)
- Every inter-service HTTP/gRPC call is wrapped in mutual TLS.
- Envoy sidecars negotiate cryptographic identity. Even if an attacker taps internal VPC network packets, all inter-service traffic is indecipherable.

### 2. Kubernetes Network Policies
- Strict network rules isolate services.
- `payment-service` has zero network route to `account-service`.
- Only `transaction-service` has firewall permissions to send traffic to `account-service:8081`.

---

## 5.6 Compliance & Regulatory Auditability

To comply with financial regulatory frameworks (**PCI-DSS 4.0**, **SOC 2 Type II**, **RBI Cyber Security Framework**):

```mermaid
graph LR
    Action["Financial Action<br/>(e.g., Transfer $500)"] --> Ledger["Immutable Audit Ledger<br/>(Kafka Topic: audit-events)"]
    Ledger --> WORM["WORM Storage (Amazon S3 Object Lock)<br/>Write Once, Read Many — Immutable for 7 Years"]
    Ledger --> SIEM["SIEM (Splunk / Elastic)<br/>Real-Time Security Event Monitoring"]
```

1. **Non-Repudiation Audit Log**:
   - Every financial state transition publishes an immutable event with:
     - `eventId`, `timestamp`, `initiatorUserId`, `sourceIp`, `action`, `beforeState`, `afterState`, and `cryptographicSignature`.
2. **Log Redaction & Sanitization**:
   - Automated Logback filters scan standard stdout/stderr for regex patterns matching Credit Card PANs, bank account numbers, and OTPs, replacing them with `****-****-****-1234` before reaching logging collectors.
