package com.ticketresale.listing;

import java.math.BigDecimal;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import jakarta.persistence.LockModeType;

public interface ListingRepository extends JpaRepository<Listing, Long> {

    /**
     * Активні оголошення події. Проєкція одразу в DTO (один SQL-запит, без N+1),
     * використовує індекс ix_listings_event_status_price.
     */
    @Query(value = """
            select new com.ticketresale.listing.ListingResponse(
                l.id, t.id, t.seat, l.event.id, l.seller.id, l.price, l.status, l.createdAt)
            from Listing l join l.ticket t
            where l.event.id = :eventId
              and l.status = com.ticketresale.listing.ListingStatus.ACTIVE
              and (:maxPrice is null or l.price <= :maxPrice)
            """,
            countQuery = """
            select count(l) from Listing l
            where l.event.id = :eventId
              and l.status = com.ticketresale.listing.ListingStatus.ACTIVE
              and (:maxPrice is null or l.price <= :maxPrice)
            """)
    Page<ListingResponse> findActiveByEvent(Long eventId, BigDecimal maxPrice, Pageable pageable);

    /** SELECT ... FOR UPDATE — серіалізує конкурентні спроби купити те саме оголошення. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select l from Listing l where l.id = :id")
    Optional<Listing> findByIdForUpdate(Long id);

    boolean existsByTicketIdAndStatus(Long ticketId, ListingStatus status);
}
