package com.ticketresale.listing;

import java.math.BigDecimal;
import java.math.RoundingMode;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ticketresale.common.ResaleRules;
import com.ticketresale.common.exception.BusinessRuleException;
import com.ticketresale.common.exception.ConflictException;
import com.ticketresale.common.exception.ForbiddenException;
import com.ticketresale.common.exception.NotFoundException;
import com.ticketresale.event.Event;
import com.ticketresale.event.EventService;
import com.ticketresale.listing.ListingDtos.CreateListingRequest;
import com.ticketresale.ticket.Ticket;
import com.ticketresale.ticket.TicketService;

@Service
public class ListingService {

    private final ListingRepository listings;
    private final TicketService ticketService;
    private final EventService eventService;
    private final ResaleRules rules;

    public ListingService(ListingRepository listings, TicketService ticketService,
                          EventService eventService, ResaleRules rules) {
        this.listings = listings;
        this.ticketService = ticketService;
        this.eventService = eventService;
        this.rules = rules;
    }

    @Transactional
    public Listing create(Long sellerId, CreateListingRequest r) {
        Ticket ticket = ticketService.get(r.ticketId());
        if (!ticket.isOwnedBy(sellerId)) {
            throw new ForbiddenException("Виставити на продаж можна лише власний квиток");
        }
        Event event = ticket.getEvent();
        if (event.hasStarted()) {
            throw new BusinessRuleException("Подія вже почалася — перепродаж неможливий");
        }
        BigDecimal maxPrice = maxAllowedPrice(event);
        if (r.price().compareTo(maxPrice) > 0) {
            throw new BusinessRuleException("Ціна перевищує допустимий максимум " + maxPrice
                    + " (номінал " + event.getFaceValue() + " × " + rules.maxMarkup() + ")");
        }
        if (listings.existsByTicketIdAndStatus(ticket.getId(), ListingStatus.ACTIVE)) {
            throw new ConflictException("Для цього квитка вже є активне оголошення");
        }
        return listings.save(new Listing(ticket, ticket.getOwner(), r.price()));
    }

    @Transactional(readOnly = true)
    public Page<ListingResponse> findActiveByEvent(Long eventId, BigDecimal maxPrice, Pageable pageable) {
        eventService.get(eventId);
        return listings.findActiveByEvent(eventId, maxPrice, pageable);
    }

    @Transactional(readOnly = true)
    public Listing get(Long id) {
        return listings.findById(id).orElseThrow(() -> new NotFoundException("Оголошення", id));
    }

    @Transactional
    public void cancel(Long listingId, Long requesterId) {
        Listing listing = listings.findByIdForUpdate(listingId)
                .orElseThrow(() -> new NotFoundException("Оголошення", listingId));
        if (!listing.getSeller().getId().equals(requesterId)) {
            throw new ForbiddenException("Скасувати оголошення може лише продавець");
        }
        listing.cancel();
    }

    private BigDecimal maxAllowedPrice(Event event) {
        return event.getFaceValue().multiply(rules.maxMarkup()).setScale(2, RoundingMode.HALF_UP);
    }
}
