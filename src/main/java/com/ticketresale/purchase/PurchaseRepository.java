package com.ticketresale.purchase;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PurchaseRepository extends JpaRepository<Purchase, Long> {

    /** Використовує індекс ix_purchases_buyer_event. */
    long countByBuyerIdAndEventId(Long buyerId, Long eventId);

    List<Purchase> findByBuyerIdOrderByCreatedAtDesc(Long buyerId);
}
