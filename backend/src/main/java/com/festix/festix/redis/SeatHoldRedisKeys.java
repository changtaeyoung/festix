package com.festix.festix.redis;

import java.util.Optional;

public final class SeatHoldRedisKeys {

    private static final String PREFIX = "seat:hold:";

    private SeatHoldRedisKeys() {
    }

    public static String key(Long seatId) {
        return PREFIX + seatId;
    }

    public static Optional<Long> parseSeatId(String key) {
        if (key == null || !key.startsWith(PREFIX)) {
            return Optional.empty();
        }
        try {
            return Optional.of(Long.parseLong(key.substring(PREFIX.length())));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }
}
