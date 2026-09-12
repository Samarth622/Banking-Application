# 🏛️ High Level Design — Banking Microservices System

> A complete, interview-ready High Level Design reference covering architecture, scalability, availability, security, data strategy, deployment, and capacity planning for the banking microservices platform.

---

## 📖 Reading Order

| # | Document | What You'll Learn |
|---|---|---|
| 1 | [System Architecture](01-system-architecture.md) | Bird's-eye view, service boundaries, communication patterns, request lifecycle |
| 2 | [Technology Decisions](02-technology-decisions.md) | Why each technology was chosen, alternatives evaluated, trade-off matrices |
| 3 | [Scalability Design](03-scalability-design.md) | Horizontal scaling, database partitioning, caching layers, Kafka partitioning, load balancing |
| 4 | [Availability & Reliability](04-availability-and-reliability.md) | Fault tolerance, circuit breakers, retries, failover, disaster recovery, SLA targets |
| 5 | [Security Design](05-security-design.md) | Authentication, authorization, encryption, API security, data protection, threat model |
| 6 | [Data Architecture](06-data-architecture.md) | Data ownership, consistency models, event sourcing, CQRS considerations, backup strategy |
| 7 | [Deployment & DevOps](07-deployment-and-devops.md) | CI/CD pipelines, containerization, orchestration, monitoring, alerting, observability |
| 8 | [Capacity Planning](08-capacity-planning.md) | Traffic estimates, throughput calculations, infrastructure sizing, cost modeling |

---

## 🎯 HLD vs LLD — When to Use Which

| Aspect | HLD (This Folder) | LLD ([../LLD](../LLD)) |
|---|---|---|
| **Audience** | Architects, Tech Leads, Interviewers | Developers, Code Reviewers |
| **Abstraction** | Services as boxes, flows as arrows | Classes, methods, fields, line-by-line |
| **Question it answers** | "How does the system work?" | "How is each piece implemented?" |
| **Diagrams** | Architecture, deployment, network | Class, sequence, ER |
| **Interview stage** | System Design Round | Machine Coding / LLD Round |

---

## 🏗️ System at a Glance

```
┌──────────────────────────────────────────────────────────────────────┐
│                         CLIENTS                                      │
│              (Mobile App, Web App, Third-Party APIs)                  │
└──────────────────────┬───────────────────────────────────────────────┘
                       │ HTTPS
                       ▼
┌──────────────────────────────────────────────────────────────────────┐
│                    API GATEWAY (:8080)                                │
│              Routing · Rate Limiting · Health Checks                 │
└────────┬──────────────────┬───────────────────┬──────────────────────┘
         │                  │                   │
         ▼                  ▼                   ▼
┌──────────────┐  ┌──────────────────┐  ┌──────────────────┐
│   Account    │  │   Transaction    │  │    Payment       │
│   Service    │  │    Service       │  │    Service       │
│   (:8081)    │  │    (:8082)       │  │    (:8083)       │
└──────┬───────┘  └───────┬──────────┘  └───────┬──────────┘
       │                  │                     │
       │           ┌──────┴──────┐              │
       │           ▼             ▼              │
       │  ┌──────────────┐ ┌──────────┐        │
       │  │    Fraud     │ │  Redis   │        │
       │  │  Detection   │ │ (Cache)  │        │
       │  │  (:8084)     │ └──────────┘        │
       │  └──────────────┘                     │
       │           │                           │
       │           ▼                           │
       │  ┌──────────────┐           ┌─────────────┐
       │  │ Notification │           │  Razorpay   │
       │  │   Service    │           │  (External) │
       │  │  (:8085)     │           └─────────────┘
       │  └──────────────┘
       ▼
┌──────────────────────────────────────────────────────────────────────┐
│                    DATA LAYER                                        │
│         MySQL (account_db, transaction_db) · Redis · Kafka           │
└──────────────────────────────────────────────────────────────────────┘
```
