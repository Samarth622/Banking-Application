# 🏦 Banking Microservices System

A production-grade distributed banking system engineered with **Java 17**, **Spring Boot 4.1.0**, **Spring Cloud 2025.1.2**, **Apache Kafka**, **Redis**, and **MySQL 8.0**.

---

## 📚 Complete Engineering Documentation

This repository contains comprehensive design documentation prepared for system architects, developers, and technical interviews:

| Documentation Suite | Description | Link |
|---|---|---|
| **High Level Design (HLD)** | System architecture, scalability, availability, security, data strategy, deployment, and capacity planning. | [Explore HLD](HLD/README.md) |
| **Low Level Design (LLD)** | Class diagrams, sequence diagrams, design patterns, Kafka event contracts, database schemas, and API design. | [Explore LLD](LLD/README.md) |

---

## 🏛️ System Architecture

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

---

## 🚀 Microservices Portfolio

| Service | Port | Description | Database / Cache |
|---|---|---|---|
| [**API-gateway**](API-gateway) | `8080` | Spring Cloud Gateway, request routing, rate limiting | Redis |
| [**account-service**](account-service) | `8081` | Account management, balance checks, account freeze | MySQL (`account_db`) |
| [**transaction-service**](transaction-service) | `8082` | Transfer orchestration, SAGA compensation, OTP validation | MySQL (`transaction_db`), Redis |
| [**payment-service**](payment-service) | `8083` | External payment gateway integration (Razorpay) | MySQL |
| [**fraud-detection-service**](fraud-detection-service) | `8084` | Real-time velocity checks, amount anomaly scoring | Redis, Kafka |
| [**notification-service**](notification-service) | `8085` | Multi-channel alert dispatch (SMS/Email simulation) | Kafka |

---

## ⚡ Quickstart (Local Development)

### 1. Prerequisites
- Java 17+
- Maven 3.8+
- Docker & Docker Compose

### 2. Start Infrastructure
```bash
docker-compose up -d
```
Starts MySQL 8.0, Redis, Zookeeper, and Apache Kafka.

### 3. Build & Run Services
Each microservice is an independent Maven project:
```bash
# In each service directory:
mvn clean spring-boot:run
```
