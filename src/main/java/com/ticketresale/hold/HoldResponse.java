package com.ticketresale.hold;

/**
 * @param expiresInSeconds залишок часу бронювання за даними Redis
 */
public record HoldResponse(Long listingId, Long buyerId, long expiresInSeconds) {
}
