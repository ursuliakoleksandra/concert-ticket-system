package com.ticketresale.event;

import java.math.BigDecimal;
import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "events")
public class Event {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String title;

    @Column(nullable = false)
    private String venue;

    @Column(name = "starts_at", nullable = false)
    private Instant startsAt;

    /** Номінальна ціна квитка — основа для обмеження ціни перепродажу. */
    @Column(name = "face_value", nullable = false, precision = 10, scale = 2)
    private BigDecimal faceValue;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected Event() {
    }

    public Event(String title, String venue, Instant startsAt, BigDecimal faceValue) {
        update(title, venue, startsAt, faceValue);
    }

    public final void update(String title, String venue, Instant startsAt, BigDecimal faceValue) {
        this.title = title;
        this.venue = venue;
        this.startsAt = startsAt;
        this.faceValue = faceValue;
    }

    public boolean hasStarted() {
        return !startsAt.isAfter(Instant.now());
    }

    public Long getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public String getVenue() {
        return venue;
    }

    public Instant getStartsAt() {
        return startsAt;
    }

    public BigDecimal getFaceValue() {
        return faceValue;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
