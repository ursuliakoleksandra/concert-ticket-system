package com.ticketresale.ticket;

import java.time.Instant;

import com.ticketresale.event.Event;
import com.ticketresale.user.AppUser;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "tickets")
public class Ticket {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "event_id")
    private Event event;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "owner_id")
    private AppUser owner;

    @Column(nullable = false)
    private String seat;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected Ticket() {
    }

    public Ticket(Event event, AppUser owner, String seat) {
        this.event = event;
        this.owner = owner;
        this.seat = seat;
    }

    public boolean isOwnedBy(Long userId) {
        return owner.getId().equals(userId);
    }

    /** Передача права власності новому покупцю після перепродажу. */
    public void transferTo(AppUser newOwner) {
        this.owner = newOwner;
    }

    public Long getId() {
        return id;
    }

    public Event getEvent() {
        return event;
    }

    public AppUser getOwner() {
        return owner;
    }

    public String getSeat() {
        return seat;
    }
}
