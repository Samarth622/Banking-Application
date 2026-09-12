# 📐 Low Level Design — Banking Microservices System

> A complete, interview-ready Low Level Design reference for an event-driven banking system built with Spring Boot, Kafka, Redis, MySQL, and Razorpay.

---

## 📖 Reading Order

| # | Document | What You'll Learn |
|---|---|---|
| 1 | [System Overview](01-system-overview.md) | Architecture style, service decomposition rationale, tech stack justification, deployment topology |
| 2 | [Database Design](02-database-design.md) | ER diagrams, table schemas, column rationale, indexing strategy, data integrity constraints |
| 3 | [Class Diagrams](03-class-diagrams.md) | Per-service class hierarchy, relationships, responsibilities, layer separation |
| 4 | [API Design](04-api-design.md) | REST contracts, HTTP methods, request/response schemas, validation rules, status codes |
| 5 | [Sequence Diagrams](05-sequence-diagrams.md) | Step-by-step flow for every operation — happy path, fraud detection, OTP, compensation |
| 6 | [Design Patterns](06-design-patterns.md) | SAGA, API Gateway, Event-Driven Architecture, Repository, Builder, DTO, Feign Client |
| 7 | [Kafka Event Design](07-kafka-event-design.md) | Topic taxonomy, event schemas, consumer groups, serialization, delivery guarantees |
| 8 | [Error Handling & Edge Cases](08-error-handling-and-edge-cases.md) | Failure scenarios, SAGA compensation, idempotency, race conditions, retry strategies |

---

## 🏗️ Quick Architecture Recap

```
Client → API Gateway (:8080) → Account Service (:8081)
                              → Transaction Service (:8082) ←→ Fraud Detection (:8084)
                              → Payment Service (:8083)
                                                            → Notification Service (:8085)

Infrastructure: MySQL 8.0 | Apache Kafka | Redis | Razorpay
```

---

## 🎯 How to Use This for Interview Prep

1. **Start with System Overview** — understand WHY each service exists
2. **Study Database Design** — most LLD interviews begin here
3. **Master Sequence Diagrams** — interviewers love tracing data flow
4. **Know Design Patterns** — explain SAGA, event sourcing, compensation
5. **Understand Error Handling** — distinguishes senior from junior thinking
