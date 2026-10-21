package com.ticketresale.listing;

import java.math.BigDecimal;
import java.time.Instant;

import com.ticketresale.common.exception.ConflictException;
import com.ticketresale.event.Event;
import com.ticketresale.ticket.Ticket;
import com.ticketresale.user.AppUser;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * Оголошення про перепродаж конкретного квитка.
 * event_id денормалізовано з ticket для швидкого пошуку оголошень події без JOIN.
 */
@Entity
@Table(name = "listings")
public class Listing {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "ticket_id")
    private Ticket ticket;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "event_id")
    private Event event;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "seller_id")
    private AppUser seller;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal price;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ListingStatus status = ListingStatus.ACTIVE;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected Listing() {
    }

    public Listing(Ticket ticket, AppUser seller, BigDecimal price) {
        this.ticket = ticket;
        this.event = ticket.getEvent();
        this.seller = seller;
        this.price = price;
    }

    public boolean isActive() {
        return status == ListingStatus.ACTIVE;
    }

    public void markSold() {
        requireActive();
        this.status = ListingStatus.SOLD;
    }

    public void cancel() {
        requireActive();
        this.status = ListingStatus.CANCELLED;
    }

    private void requireActive() {
        if (!isActive()) {
            throw new ConflictException("Оголошення id=" + id + " вже неактивне (статус " + status + ")");
        }
    }

    public Long getId() {
        return id;
    }

    public Ticket getTicket() {
        return ticket;
    }

    public Event getEvent() {
        return event;
    }

    public AppUser getSeller() {
        return seller;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public ListingStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
