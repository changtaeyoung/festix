package com.festix.festix.domain.reservation.service;

import com.festix.festix.domain.reservation.config.ReservationProperties;
import com.festix.festix.domain.reservation.entity.Reservation;
import com.festix.festix.domain.reservation.exception.ReservationNotFoundException;
import com.festix.festix.domain.reservation.repository.ReservationItemRepository;
import com.festix.festix.domain.reservation.repository.ReservationRepository;
import com.festix.festix.redis.SeatHoldRedisWriter;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * One-time TTL extension applied when a user starts payment. Symmetric twin
 * of {@link SeatHoldExpiryCoordinator}: it owns the "extend the hold"
 * cross-store transition and its transaction boundary, so PaymentService
 * never touches Redis or ReservationRepository directly.
 *
 * <p>Ordering is the reverse of the hold path (which is Postgres-then-Redis
 * via {@link SeatHoldFacade}): here Redis is updated first, and only if that
 * succeeds is Postgres end_ttl advanced. Doing it the other way round risks
 * the UI (which reads Postgres end_ttl) showing time remaining on a seat
 * Redis is about to release.
 *
 * <p>Runs in its own transaction ({@code REQUIRES_NEW}): the Redis keys have
 * already been mutated by the time the Postgres UPDATE runs, so that UPDATE
 * must commit independently of whatever the caller does afterwards. If it
 * instead joined startPayment's transaction, a later failure there would roll
 * back end_ttl while Redis stayed extended — and the safety-net batch, which
 * scans Postgres end_ttl, would then release a seat that is still
 * legitimately held.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SeatHoldTtlExtender {

    private final ReservationRepository reservationRepository;
    private final ReservationItemRepository reservationItemRepository;
    private final SeatHoldRedisWriter seatHoldRedisWriter;
    private final ReservationProperties reservationProperties;

    /**
     * Extends the reservation's hold TTL exactly once. No-op if it has
     * already been extended. Throws {@link SeatHoldTtlExtensionException} if
     * Redis cannot be updated — the caller must then abort without creating
     * the payment.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void extendOnPaymentStart(Long reservationId) {
        Reservation reservation = reservationRepository.findById(reservationId)
                .orElseThrow(() -> new ReservationNotFoundException(reservationId));

        if (reservation.isExtended()) {
            // One-time cap already spent — touch neither Redis nor Postgres.
            return;
        }

        // Full reset to the original hold duration, not a short bump.
        LocalDateTime newEndTtl = LocalDateTime.now().plusMinutes(reservationProperties.holdTtlMinutes());

        List<Long> seatIds = reservationItemRepository.findByReservationId(reservationId).stream()
                .map(item -> item.getSeat().getId())
                .sorted()
                .toList();

        // Redis first. Throws on any missing / re-owned key, before Postgres.
        seatHoldRedisWriter.resetHoldTtl(reservationId, seatIds, newEndTtl);

        int updated = reservationRepository.extendTtl(reservationId, newEndTtl);
        if (updated == 0) {
            // A concurrent payment-start already extended this reservation.
            // Redis was just reset to an equivalent instant, so there is
            // nothing to fix — the once-only rule still held.
            log.debug("Reservation {} already extended by a concurrent payment start", reservationId);
        }
    }
}
