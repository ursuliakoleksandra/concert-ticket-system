package com.ticketresale.ticket;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface TicketRepository extends JpaRepository<Ticket, Long> {

    boolean existsByEventId(Long eventId);

    /** JOIN FETCH події — щоб уникнути N+1 при формуванні відповіді. */
    @Query("select t from Ticket t join fetch t.event where t.owner.id = :ownerId order by t.event.startsAt")
    List<Ticket> findAllByOwnerWithEvent(Long ownerId);
}
