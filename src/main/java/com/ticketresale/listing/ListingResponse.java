package com.ticketresale.listing;

import java.math.BigDecimal;
import java.time.Instant;

/** Окремий top-level record, бо використовується в JPQL constructor expression. */
public record ListingResponse(Long id, Long ticketId, String seat, Long eventId, Long sellerId,
                              BigDecimal price, ListingStatus status, Instant createdAt) {

    public static ListingResponse from(Listing l) {
        return new ListingResponse(l.getId(), l.getTicket().getId(), l.getTicket().getSeat(),
                l.getEvent().getId(), l.getSeller().getId(), l.getPrice(), l.getStatus(), l.getCreatedAt());
    }
}
