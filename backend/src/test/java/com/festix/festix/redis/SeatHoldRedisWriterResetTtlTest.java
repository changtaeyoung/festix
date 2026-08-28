package com.festix.festix.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.festix.festix.domain.reservation.SeatHoldTtlExtensionException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

@ExtendWith(MockitoExtension.class)
class SeatHoldRedisWriterResetTtlTest {

    private static final long RESERVATION_ID = 42L;
    private static final List<Long> SEAT_IDS = List.of(10L, 20L);

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private SimpleMeterRegistry meterRegistry;
    private SeatHoldRedisWriter writer;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        writer = new SeatHoldRedisWriter(redisTemplate, meterRegistry);
    }

    private void givenCurrentKeyValues(String... values) {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.multiGet(anyList())).thenReturn(Arrays.asList(values));
    }

    @Test
    void appliesPipelinedExpiry_whenEveryKeyExistsAndMatches() {
        givenCurrentKeyValues("42", "42");
        when(redisTemplate.executePipelined(any(RedisCallback.class)))
                .thenReturn(List.of(Boolean.TRUE, Boolean.TRUE));

        assertThatCode(() -> writer.resetHoldTtl(RESERVATION_ID, SEAT_IDS, LocalDateTime.now().plusMinutes(5)))
                .doesNotThrowAnyException();

        verify(redisTemplate).executePipelined(any(RedisCallback.class));
        assertThat(meterRegistry.counter("seat_hold.redis_extend.failures").count()).isZero();
    }

    @Test
    void throwsWithoutMutating_whenAKeyIsMissing() {
        givenCurrentKeyValues("42", null);

        assertThatThrownBy(() -> writer.resetHoldTtl(RESERVATION_ID, SEAT_IDS, LocalDateTime.now().plusMinutes(5)))
                .isInstanceOf(SeatHoldTtlExtensionException.class)
                .hasMessageContaining("seatId=20");

        verify(redisTemplate, never()).executePipelined(any(RedisCallback.class));
        assertThat(meterRegistry.counter("seat_hold.redis_extend.failures").count()).isEqualTo(1.0);
    }

    @Test
    void throwsWithoutMutating_whenAKeyIsOwnedByAnotherReservation() {
        givenCurrentKeyValues("42", "999");

        assertThatThrownBy(() -> writer.resetHoldTtl(RESERVATION_ID, SEAT_IDS, LocalDateTime.now().plusMinutes(5)))
                .isInstanceOf(SeatHoldTtlExtensionException.class)
                .hasMessageContaining("reservationId=999");

        verify(redisTemplate, never()).executePipelined(any(RedisCallback.class));
    }

    @Test
    void throws_whenPipelineReportsAKeyExpiredMidExtension() {
        givenCurrentKeyValues("42", "42");
        when(redisTemplate.executePipelined(any(RedisCallback.class)))
                .thenReturn(List.of(Boolean.TRUE, Boolean.FALSE));

        assertThatThrownBy(() -> writer.resetHoldTtl(RESERVATION_ID, SEAT_IDS, LocalDateTime.now().plusMinutes(5)))
                .isInstanceOf(SeatHoldTtlExtensionException.class)
                .hasMessageContaining("seatId=20");

        assertThat(meterRegistry.counter("seat_hold.redis_extend.failures").count()).isEqualTo(1.0);
    }
}
