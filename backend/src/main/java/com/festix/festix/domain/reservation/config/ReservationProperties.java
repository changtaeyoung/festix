package com.festix.festix.domain.reservation.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Bound from {@code festix.reservation.*}. Holds the business-level hold TTL
 * (how long a successful holder keeps a seat) and the DB-level lock-wait
 * timeout (how long a transaction waits to acquire a {@code FOR UPDATE} lock)
 * — two separate concepts that both need to be read from config rather than
 * hardcoded. The safety-net interval stays inline on {@code @Scheduled}
 * because that annotation needs a constant SpEL string.
 */
@ConfigurationProperties(prefix = "festix.reservation")
public record ReservationProperties(long holdTtlMinutes, long lockTimeoutMillis) {
}
