# 07 — Deployment & DevOps

## 7.1 Production Infrastructure Architecture

The banking platform is deployed on managed Kubernetes (AWS EKS / Google GKE) spanning **3 Availability Zones (AZs)** for high availability, zero-downtime rolling upgrades, and infrastructure-as-code automation.

```mermaid
graph TB
    subgraph "External Traffic"
        Client["Clients (Mobile / Web)"] --> Route53["Route 53 / Cloudflare DNS"]
        Route53 --> WAF["AWS WAF"]
        WAF --> NLB["AWS Network Load Balancer (NLB)"]
    end

    subgraph "Kubernetes Cluster (EKS / GKE Multi-AZ)"
        NLB --> Ingress["Ingress Controller (Nginx / Envoy)"]
        
        subgraph "Namespace: banking-core"
            Ingress --> GW_PODS["API Gateway Pods (x3)<br/>Port 8080"]
            GW_PODS --> AS_PODS["Account Service Pods (x3)<br/>Port 8081"]
            GW_PODS --> TS_PODS["Transaction Service Pods (x4)<br/>Port 8082"]
            GW_PODS --> PS_PODS["Payment Service Pods (x2)<br/>Port 8083"]
            TS_PODS -.-> FD_PODS["Fraud Detection Pods (x3)<br/>Port 8084"]
            TS_PODS -.-> NS_PODS["Notification Pods (x2)<br/>Port 8085"]
        end

        subgraph "Namespace: banking-infra"
            KAFKA_CLUSTER["Kafka Strimzi Cluster (3 Brokers + 3 ZK)"]
            REDIS_CLUSTER["Redis Sentinel / Cluster (3 Nodes)"]
        end
    end

    subgraph "Managed Data Tier (AWS RDS Multi-AZ)"
        AS_PODS --> RDS_ACC[("RDS MySQL: account_db")]
        TS_PODS --> RDS_TXN[("RDS MySQL: transaction_db")]
    end
```

### Namespace Isolation & Network Policies
- `banking-core`: Business microservice pods.
- `banking-infra`: Shared internal middleware (Kafka, Redis).
- `banking-monitoring`: Observability stack (Prometheus, Grafana, OpenTelemetry).
- **NetworkPolicy**: Denies all cross-namespace traffic by default; strictly whitelists port communication (e.g. only `TS_PODS` can open connections to `AS_PODS:8081`).

---

## 7.2 CI/CD Pipeline & GitOps Workflow

Code transitions from developer commit to production through a fully automated, immutable container pipeline governed by **GitOps** principles.

```mermaid
sequenceDiagram
    autonumber
    participant Dev as Developer
    participant Git as GitHub Repository
    participant CI as GitHub Actions (CI)
    participant Sec as Security Scanners (SonarQube + Trivy)
    participant Registry as Container Registry (ECR / Harbor)
    participant Argo as ArgoCD (GitOps Operator)
    participant K8s as Kubernetes Cluster

    Dev->>Git: 1. Push code to `main` via PR
    Git->>CI: 2. Trigger Automated CI Pipeline
    CI->>CI: 3. Maven clean verify (Unit + Integration Tests)
    CI->>Sec: 4. Static Code Analysis & SAST Check
    Sec-->>CI: Quality Gate Passed
    CI->>CI: 5. Build Docker Image (Distroless Java 17)
    CI->>Sec: 6. Scan Image Vulnerabilities (CVE check)
    Sec-->>CI: Image Scan Clear (0 High/Critical)
    CI->>Registry: 7. Push Signed Container with Git SHA tag
    CI->>Git: 8. Update GitOps Manifest Repo with new Tag
    Argo->>Git: 9. Detect Manifest Change (Polling / Webhook)
    Argo->>K8s: 10. Reconcile Desired State (Progressive Deployment)
```

---

## 7.3 Progressive Deployment Strategies

Financial systems cannot tolerate full-cluster restarts or downtime during releases. We utilize **Canary Deployments** managed by Argo Rollouts.

```mermaid
graph LR
    subgraph "Phase 1: Baseline (100% Traffic to v1.0.0)"
        Stable1["v1.0.0 (100% Traffic)"]
    end

    subgraph "Phase 2: Canary Launch (10% Traffic to v1.1.0)"
        Stable2["v1.0.0 (90% Traffic)"]
        Canary2["v1.1.0 (10% Traffic)"]
    end

    subgraph "Phase 3: Automated Analysis (Prometheus Metric Check)"
        Analysis{"HTTP 5xx Error Rate > 0.1%<br/>OR p95 Latency > 500ms?"}
    end

    subgraph "Phase 4a: Rollback (Failure)"
        Abort["Immediate Instant Rollback to v1.0.0"]
    end

    subgraph "Phase 4b: Promotion (Success)"
        Promote["Promote v1.1.0 to 100% Traffic"]
    end

    Phase1 --> Phase2 --> Analysis
    Analysis -->|"YES (Anomaly)"| Abort
    Analysis -->|"NO (Healthy)"| Promote
```

### Argo Rollout Configuration (Canary)
```yaml
apiVersion: argoproj.io/v1alpha1
kind: Rollout
metadata:
  name: transaction-service
spec:
  replicas: 10
  strategy:
    canary:
      analysis:
        templates:
          - templateName: success-rate-check
        args:
          - name: service-name
            value: transaction-service
      steps:
        - setWeight: 10
        - pause: { duration: 10m }     # Soak time with 10% live traffic
        - setWeight: 50
        - pause: { duration: 15m }     # Soak time with 50% live traffic
        - setWeight: 100
```

---

## 7.4 Observability: The Three Pillars

A complex distributed banking system cannot be debugged with SSH or basic logs. Full telemetry is enforced across all 5 microservices:

```mermaid
graph TD
    subgraph "The Three Pillars of Observability"
        M["1. Metrics (Time-Series)<br/>━━━━━━━━━━━━━━━━━━<br/>Prometheus + Grafana<br/>Answers: 'Is there a problem?'<br/>• Error Rate (HTTP 5xx)<br/>• Latency (p50, p95, p99)<br/>• Throughput (QPS)<br/>• JVM Heap & GC Pauses"]
        T["2. Distributed Tracing<br/>━━━━━━━━━━━━━━━━━━<br/>OpenTelemetry + Jaeger<br/>Answers: 'Where is the problem?'<br/>• Follows request from Gateway<br/>  to Service A to Kafka to Service B<br/>• Identifies exact slow bottleneck"]
        L["3. Centralized Logging<br/>━━━━━━━━━━━━━━━━━━<br/>FluentBit + OpenSearch / ELK<br/>Answers: 'Why did it happen?'<br/>• Structured JSON logs<br/>• Correlated with traceId & spanId"]
    end
```

### Distributed Tracing Flow (W3C Trace Context)
Every inbound HTTP request receives or inherits a `traceparent` header. This identifier is injected into SLF4J MDC and Kafka Record Headers:

```mermaid
sequenceDiagram
    autonumber
    participant GW as API Gateway
    participant TS as Transaction Service
    participant K as Kafka
    participant FD as Fraud Service

    GW->>GW: Generate traceId = "4bf92f3577b34da6a3ce929d0e0e4736"
    GW->>TS: Forward HTTP + Header traceparent: 00-4bf92...-01
    TS->>TS: Log: "[traceId=4bf92...] Processing transfer"
    TS->>K: Publish event + Record Header traceparent: 00-4bf92...-02
    K->>FD: Consume event + Extract traceparent
    FD->>FD: Log: "[traceId=4bf92...] Fraud scoring evaluated"
```
*Value*: Searching `4bf92f3577b34da6a3ce929d0e0e4736` in Jaeger or Kibana instantly displays the complete end-to-end journey across all 4 services on a single screen.

---

## 7.5 Health Checks & Self-Healing

Kubernetes actively monitors pod lifecycle using three specialized probes:

```mermaid
graph LR
    Startup["Startup Probe<br/>(/actuator/health/liveness)<br/>Gives JVM 60s to boot before checking health"]
    Liveness["Liveness Probe<br/>(/actuator/health/liveness)<br/>Restarts container if deadlock or fatal loop occurs"]
    Readiness["Readiness Probe<br/>(/actuator/health/readiness)<br/>Removes pod from load balancer if DB/Kafka pool drops"]

    Startup --> Liveness --> Readiness
```

### Pod Disruption Budgets (PDB)
To prevent accidental outages during Kubernetes cluster node maintenance or automated cluster scaling:
```yaml
apiVersion: policy/v1
kind: PodDisruptionBudget
metadata:
  name: transaction-service-pdb
spec:
  minAvailable: 70%
  selector:
    matchLabels:
      app: transaction-service
```
*Guarantee*: Kubernetes cluster upgrades will never evict more than 30% of running transaction service pods at any given time.
