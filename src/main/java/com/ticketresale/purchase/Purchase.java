package com.ticketresale.purchase;

import java.math.BigDecimal;
import java.time.Instant;

import com.ticketresale.listing.Listing;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Факт купівлі квитка через перепродаж. Ціна, продавець і подія фіксуються
 * на момент угоди (історичний запис, не змінюється разом з оголошенням).
 */
@Entity
@Table(name = "purchases")
public class Purchase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "listing_id", nullable = false, unique = true)
    private Long listingId;

    @Column(name = "buyer_id", nullable = false)
    private Long buyerId;

    @Column(name = "seller_id", nullable = false)
    private Long sellerId;

    @Column(name = "event_id", nullable = false)
    private Long eventId;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal price;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected Purchase() {
    }

    public Purchase(Listing listing, Long buyerId) {
        this.listingId = listing.getId();
        this.buyerId = buyerId;
        this.sellerId = listing.getSeller().getId();
        this.eventId = listing.getEvent().getId();
        this.price = listing.getPrice();
    }

    public Long getId() {
        return id;
    }

    public Long getListingId() {
        return listingId;
    }

    public Long getBuyerId() {
        return buyerId;
    }

    public Long getSellerId() {
        return sellerId;
    }

    public Long getEventId() {
        return eventId;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
