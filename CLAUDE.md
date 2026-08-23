# Festix — Real-time Seat-Reservation Festival Ticketing System

Portfolio project. The core proof points are **concurrency control** and **infrastructure understanding**; real-service polish (payment UX, etc.) is not the priority.

## Tech Stack
- Spring Boot **4.1.1**, Java 17, Gradle
- **Gradle project root is `backend/`, not the repo root** — run all `./gradlew` commands from inside `backend/` (e.g. `cd backend && ./gradlew bootRun`)
- Dependencies (as configured in `backend/build.gradle`):
  - `spring-boot-starter-actuator`
  - `spring-boot-starter-data-jpa`
  - `spring-boot-starter-data-redis`
  - `spring-boot-starter-flyway` + `flyway-database-postgresql`
  - `spring-boot-starter-validation`
  - `spring-boot-starter-webmvc` (renamed from `-web` in Boot 4)
  - `spring-boot-starter-websocket`
  - `postgresql` (runtime)
  - `micrometer-registry-prometheus` (runtime)
  - `lombok`, `spring-boot-devtools`
  - Test: per-feature test starters (`-actuator-test`, `-data-jpa-test`, `-data-redis-test`, `-flyway-test`, `-validation-test`, `-webmvc-test`, `-websocket-test`) instead of a single monolithic test starter — this is a Boot 4 change, confirm against official docs if anything behaves unexpectedly
- PostgreSQL 16, Redis 7
- WebSocket, Flyway (schema migration)
- Local dev environment: `docker-compose.yml` (Postgres:5432, Redis:6379 — container/db names use `festix` now, not `ticketing`)

## Reference Docs (read on-demand only — do not copy full content into this file)
- `docs/festix-project-brief.md` — goals, roadmap, role split
- `docs/festix-schema-reference.md` — condensed table specs, concurrency/TTL/payment rules for implementation use (read this, not the ERD notes below)
- `ddl.sql` — finalized DDL (6 tables: festival, seat, reservation, reservation_item, payment, users)

Do **not** read `docs/festix-erd-design-notes.md` unless explicitly asked — it's a Korean narrative archive for Notion (design-mistake retrospective), not an implementation reference.

## Core Design Principles (must follow)
1. **Seat status**: `seat.status` = AVAILABLE / HELD / SOLD. Always change state via conditional UPDATE (e.g. `WHERE status='HELD'`) — do not build separate priority/ordering logic.
2. **Concurrency control**: pessimistic lock via `@Lock(LockModeType.PESSIMISTIC_WRITE)`; conditional UPDATE via `@Modifying + @Query` issuing a direct query (do not rely on entity dirty-checking — the WHERE condition won't attach).
3. **Deadlock prevention**: when acquiring locks on multiple seats, always sort by seat_id ascending and acquire sequentially.
4. **Bundled reservation**: `reservation` (parent, bundle) / `reservation_item` (child, individual seat). All-or-nothing — if any seat fails, roll back the whole reservation.
5. **Baked-in vs computed**: for events that have already happened (payment amount, TTL), store the value at the time it occurred. Do not recompute an in-progress state on every read.
6. **TTL source of truth is Redis**: `reservation.end_ttl` (Postgres) is for consistency/history only; actual expiry is determined by Redis TTL + keyspace notification.

## Coding Conventions
(Not yet defined — fill in as the project progresses)

## Compact Instructions
When compacting, prioritize keeping the architecture/design decisions made so far and the list of files in progress; summarize the rest of the conversation.