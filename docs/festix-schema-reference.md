# Festix — Schema & Concurrency Reference

Condensed implementation reference for Claude Code. For the full design rationale (why each decision was made), see `docs/festix-erd-design-notes.md` (Korean, Notion archive — not meant to be read during coding).

## Tables

### users
| Column | Type | Notes |
|---|---|---|
| id | BIGSERIAL PK | |
| custom_id | VARCHAR(50) UNIQUE NOT NULL | login id |
| password | VARCHAR(255) NOT NULL | BCrypt hash |
| name | VARCHAR(100) NOT NULL | |
| phone | VARCHAR(20) NOT NULL | |
| created_date | TIMESTAMP NOT NULL DEFAULT now() | |
| updated_date | TIMESTAMP NOT NULL DEFAULT now() | |

### festival
| Column | Type | Notes |
|---|---|---|
| id | BIGSERIAL PK | |
| name | VARCHAR(200) NOT NULL | |
| event_date | DATE NOT NULL | |
| location | VARCHAR(300) NOT NULL | |

### seat
| Column | Type | Notes |
|---|---|---|
| id | BIGSERIAL PK | |
| festival_id | BIGINT NOT NULL, FK → festival.id | indexed with status |
| price | DECIMAL(10,2) NOT NULL | |
| status | VARCHAR(20) NOT NULL DEFAULT 'AVAILABLE' | CHECK IN ('AVAILABLE','HELD','SOLD') |

Index: `(festival_id, status)` — seat grid queries always filter by both.

### reservation (parent — one hold attempt, may bundle multiple seats)
| Column | Type | Notes |
|---|---|---|
| id | BIGSERIAL PK | |
| user_id | BIGINT NOT NULL, FK → users.id | |
| created_date | TIMESTAMP NOT NULL DEFAULT now() | |
| end_ttl | TIMESTAMP NOT NULL | stored at insert time, not computed on read |
| is_extended | BOOLEAN NOT NULL DEFAULT false | 1-time extension flag |

Indexes: `user_id` (my-page lookups), `end_ttl` (safety-net batch scan).

### reservation_item (child — one seat within a reservation)
| Column | Type | Notes |
|---|---|---|
| id | BIGSERIAL PK | |
| reservation_id | BIGINT NOT NULL, FK → reservation.id, ON DELETE CASCADE | |
| seat_id | BIGINT NOT NULL, FK → seat.id | **no unique constraint** — same seat can appear across many past attempts; this table is a history log, not the current-availability source |

### payment
| Column | Type | Notes |
|---|---|---|
| id | BIGSERIAL PK | |
| reservation_id | BIGINT NOT NULL, FK → reservation.id | one payment per bundle, not per seat |
| amount | DECIMAL(12,2) NOT NULL | baked in at payment confirmation time |
| status | VARCHAR(20) NOT NULL DEFAULT 'PENDING' | CHECK IN ('PENDING','CANCELED','COMPLETED','REFUNDED') |
| cancel_reason | VARCHAR(500) NULL | set only on CANCELED; distinguish trigger source, e.g. `EXPIRED_BY_EVENT` vs `EXPIRED_BY_BATCH_RECOVERY` |
| paid_at | TIMESTAMP NULL | |
| refunded_at | TIMESTAMP NULL | set only on REFUNDED |

Full DDL with inline rationale comments: `ddl.sql` at project root (comments are in Korean).

## Concurrency Control

- **Pessimistic lock**: `@Lock(LockModeType.PESSIMISTIC_WRITE)` on seat lookup (maps to `SELECT ... FOR UPDATE`). Do not use `@Version` (optimistic) — rejected early because retry storms are unacceptable at ticket-open traffic spikes.
- **Multi-seat lock ordering**: when locking several seats at once, sort by `seat.id` ascending and acquire sequentially. This is deadlock *prevention* (no circular wait possible), not a priority rule.
- **Conditional UPDATE pattern**: state transitions never use entity dirty-checking (the WHERE condition won't attach). Always use `@Modifying + @Query` issuing an explicit UPDATE, and check the affected-row count (0 = someone else already won):
  ```sql
  -- release an expired hold
  UPDATE seat SET status = 'AVAILABLE' WHERE id = ? AND status = 'HELD';
  -- refund a completed sale
  UPDATE seat SET status = 'AVAILABLE' WHERE id = ? AND status = 'SOLD';
  ```
- **All-or-nothing**: if any seat in a multi-seat reservation attempt fails to lock, roll back the entire `reservation` — never partial success. Same principle applies to refunds (bundle-level only, no partial refund).

## TTL / Expiry Lifecycle

- Redis is the source of truth for "is this hold still alive" — TTL + `notify-keyspace-events Ex` (keyspace notifications) drive real-time expiry via pub/sub. `docker-compose.yml` already enables this.
- `reservation.end_ttl` (Postgres) is for consistency/history display only, not the live check.
- **Extension**: on payment-button click, extend TTL once, full reset to the original duration (not a short +30s bump). Update **Redis first, then Postgres** — if Redis fails, abort before touching Postgres (the reverse order risks the UI showing time remaining on a seat that's actually about to release). Track via `reservation.is_extended` to cap at one extension.
- **Safety-net batch**: pub/sub can silently miss expiry events (e.g. during a deploy). A low-frequency batch (~1 min) re-scans `WHERE seat.status='HELD' AND reservation.end_ttl < NOW()` and reuses the same conditional UPDATE — no separate safety logic needed. This batch finding rows repeatedly (not just occasionally) signals a real pub/sub leak, not noise — worth a Prometheus counter.
- Whenever a hold is released (via pub/sub or the safety-net batch) and its `payment` was still `PENDING`, set that payment to `CANCELED` in the same operation — don't leave orphaned `PENDING` rows.

## Payment / Refund

- `payment` row is created only when the user actually clicks "pay" (not at seat-hold time).
- `CANCELED` = payment never completed (TTL expiry, PG timeout, user abandoned). `REFUNDED` = payment completed, then refunded on user request. Never conflate the two — they answer different business questions later (drop-off rate vs. refund rate).
- Partial refunds are out of scope by design — refund is all-or-nothing at the `reservation` level, same as the hold step.

## ORM Notes (JPA, switched from MyBatis)

- Switched before any Mapper/Repository code existed — zero migration cost.
- New risk vs. MyBatis: N+1 on lazy-loaded associations. Use `fetch join` or `@EntityGraph` where relevant; MyBatis didn't have this failure mode since it only ever ran the SQL you wrote.