# Festix — Real-time Seat-Reservation Festival Ticketing System — Project Brief

> Last updated: 2026-08-21
> Purpose: a portfolio project proving concurrency control and infrastructure (DevOps) skills

---

## 1. Project Goals

- **Core proof points**: concurrency control (locking strategy), handling traffic spikes, infrastructure understanding (Docker/CI-CD/cloud)
- Not a service for real users — no scraping real festival data; seeded directly with virtual festival names
- Top priority: being able to explain, in the candidate's own words, why each design decision was made (for interviews/cover letters)

## 2. Core Scenario

- 2–3 virtual festivals, ~100–200 seats each
- User views a seat grid and clicks a seat → status reflected in real time via WebSocket (e.g. turns red when held)
- k6 / Locust used to artificially simulate the traffic spike at "ticket open" moment

## 3. Tech Stack

| Area | Stack |
|---|---|
| Backend | Spring Boot, JPA (Hibernate), PostgreSQL, Redis |
| Concurrency control | Pessimistic lock (`SELECT ... FOR UPDATE`) + TTL |
| Real-time updates | WebSocket |
| Containers/orchestration | Docker, Docker Compose, (optional) local K8s (minikube/kind) |
| Deployment | GCP Cloud Run (free tier), DB/Redis on an always-free e2-micro VM |
| CI/CD | GitHub Actions |
| Load testing | k6 / Locust |
| Monitoring | Prometheus + Grafana |
| Frontend | Delegated to **Claude Design** (real-time seat grid UI) |

### Concurrency Control Design (Confirmed)
- Seat holding uses pessimistic lock + TTL
- JPA implementation: pessimistic lock via `@Lock(LockModeType.PESSIMISTIC_WRITE)`; conditional UPDATE (`WHERE status='HELD'`) via `@Modifying + @Query` issuing a direct query (entity dirty-checking is not used since the WHERE condition wouldn't attach)
- When reserving multiple seats at once, acquire locks **sorted by seat ID ascending, sequentially** — this eliminates deadlock (circular wait) at the source
    - The sort is not a rule for "deciding a winner" — it's a rule for preventing deadlock itself
- If any seat in a multi-seat reservation fails to be held, **roll back the whole thing and ask the user to reselect** (all-or-nothing atomicity)

## 4. Not Yet Decided — Next Steps

- [x] ERD (festival / seat / reservation / reservation_item / payment / users tables) — done
- [x] DDL — done
- [x] Docker Compose local environment — done
- [ ] API spec (seat lookup / hold / confirm endpoints)
- [ ] WebSocket message format

## 5. Role Split

| Taeyoung | Claude |
|---|---|
| Trade-off decisions (locking strategy, table structure, etc.) | Socratic questioning to probe edge cases |
| Draft ERD / API spec | Review normalization, missing columns, relationship direction |
| Final decisions | Explain concepts, convert to DDL, provide checklists |

- Rationale: design/DB skills are only meaningful for a portfolio if the candidate can explain "why" directly

## 6. Overall Roadmap

1. ERD design — done
2. DDL — done
3. Local Postgres/Redis via Docker Compose — done
4. Spring Boot initial setup + common modules (exception handling, response format, etc.) ← **current step**
5. Domain API implementation (seat lookup → hold → confirm)
6. WebSocket real-time seat status updates
7. Frontend via Claude Design + API/WebSocket integration
8. k6 / Locust load testing + benchmark comparison across locking strategies
9. Dockerize → deploy to GCP Cloud Run → GitHub Actions CI/CD
10. Prometheus / Grafana monitoring setup

## 7. Deployment Notes
- GCP Cloud Run: set a max-instances cap, min instances 0 to control cost
- No real payment integration — recommend setting a budget alert
- Cloud SQL has no permanent free tier — DB is installed directly on the e2-micro VM instead

## 8. ORM Choice: MyBatis → JPA

- Switched from MyBatis to JPA. Reason: a target company (짐싸/Jimsa) favors JPA, and it had been a while since last using it — wanted to refresh that skill. The switch happened before any Mapper XML or Repository code was written, so the migration cost was effectively zero.
- The confirmed concurrency strategy carries over unchanged: pessimistic lock via `@Lock`, conditional UPDATE via `@Modifying + @Query`.
- New risk introduced: N+1 queries from lazy-loaded associations (MyBatis doesn't have this issue since it runs exactly the SQL you write). Address with `fetch join` or `@EntityGraph` as needed during implementation.

## 9. Payment Refund Policy

- `payment.status` includes `REFUNDED`, distinct from `CANCELED` (`CANCELED` = payment never completed — TTL expiry, PG timeout, etc.; `REFUNDED` = refunded after a completed payment)
- Partial refunds (e.g. refunding 2 of 3 reserved seats) are **not supported** — refunds are all-or-nothing at the `reservation` (bundle) level, consistent with the all-or-nothing principle already used for seat holding
- Scope decision: supporting partial refunds would require additional columns (per-item cancellation flag on `reservation_item`, cumulative refunded amount on `payment`) — complexity unrelated to the project's core proof point (concurrency control), so intentionally excluded