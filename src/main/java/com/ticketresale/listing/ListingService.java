package com.ticketresale.listing;

import java.math.BigDecimal;
import java.math.RoundingMode;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ticketresale.cache.CacheResult;
import com.ticketresale.cache.ListingCache;
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
    private final ListingCache listingCache;

    public ListingService(ListingRepository listings, TicketService ticketService,
                          EventService eventService, ResaleRules rules, ListingCache listingCache) {
        this.listings = listings;
        this.ticketService = ticketService;
        this.eventService = eventService;
        this.rules = rules;
        this.listingCache = listingCache;
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
        Listing saved = listings.save(new Listing(ticket, ticket.getOwner(), r.price()));
        listingCache.invalidateAfterCommit(event.getId());
        return saved;
    }

    /**
     * Read-intensive сценарій (ЛР4): Cache-Aside через Redis.
     * Метод навмисно без @Transactional: при Cache HIT не береться з'єднання з пулу БД.
     * При MISS кожне звернення до БД виконується у власній короткій транзакції.
     */
    public CacheResult<Page<ListingResponse>> findActiveByEvent(Long eventId, BigDecimal maxPrice,
                                                                Pageable pageable) {
        return listingCache.getOrLoad(eventId, maxPrice, pageable, () -> {
            eventService.get(eventId);
            return listings.findActiveByEvent(eventId, maxPrice, pageable);
        });
    }

    @Transactional(readOnly = true)
    public Listing get(Long id) {
        return listings.findById(id).orElseThrow(() -> new NotFoundException("Оголошення", id));
    }

    /**
     * Відповідь формується всередині транзакції: зв'язки Listing → Ticket ліниві (LAZY),
     * а при open-in-view: false звернення до них після закриття транзакції неможливе.
     */
    @Transactional(readOnly = true)
    public ListingResponse getView(Long id) {
        return ListingResponse.from(get(id));
    }

    @Transactional
    public void cancel(Long listingId, Long requesterId) {
        Listing listing = listings.findByIdForUpdate(listingId)
                .orElseThrow(() -> new NotFoundException("Оголошення", listingId));
        if (!listing.getSeller().getId().equals(requesterId)) {
            throw new ForbiddenException("Скасувати оголошення може лише продавець");
        }
        listing.cancel();
        listingCache.invalidateAfterCommit(listing.getEvent().getId());
    }

    private BigDecimal maxAllowedPrice(Event event) {
        return event.getFaceValue().multiply(rules.maxMarkup()).setScale(2, RoundingMode.HALF_UP);
    }
}
