package com.ticketresale.purchase;

import java.math.BigDecimal;
import java.time.Instant;

public record PurchaseResponse(Long id, Long listingId, Long eventId, Long buyerId, Long sellerId,
                               BigDecimal price, Instant createdAt) {

    public static PurchaseResponse from(Purchase p) {
        return new PurchaseResponse(p.getId(), p.getListingId(), p.getEventId(), p.getBuyerId(),
                p.getSellerId(), p.getPrice(), p.getCreatedAt());
    }
}
