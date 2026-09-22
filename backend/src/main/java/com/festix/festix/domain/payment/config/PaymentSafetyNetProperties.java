package com.festix.festix.domain.payment.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Bound from {@code festix.payment.safety-net.*}. Holds the staleness
 * threshold (how long a payment may sit in CONFIRMING before the safety-net
 * batch cancels it) so it isn't hardcoded. The {@code interval-millis} value
 * also lives under this prefix but stays inline on {@code @Scheduled} — that
 * annotation needs a constant SpEL string — so it is intentionally not read
 * from here.
 */
@ConfigurationProperties(prefix = "festix.payment.safety-net")
public record PaymentSafetyNetProperties(long intervalMillis, long stalenessThresholdMillis) {
}
