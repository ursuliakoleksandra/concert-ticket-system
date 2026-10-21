package com.ticketresale.hold;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ticketresale.common.exception.BusinessRuleException;
import com.ticketresale.common.exception.ConflictException;
import com.ticketresale.common.exception.NotFoundException;
import com.ticketresale.listing.Listing;
import com.ticketresale.listing.ListingService;
import com.ticketresale.user.UserService;

/**
 * Тимчасове бронювання оголошення покупцем перед оплатою.
 * <p>
 * Конкуренція покупців за популярне оголошення вирішується атомарною операцією Redis SET NX
 * (O(1), без з'єднання з БД), а не чергою на блокування рядка PostgreSQL.
 * До транзакції купівлі з блокуванням доходить лише власник бронювання.
 */
@Service
public class HoldService {

    private final ListingService listingService;
    private final UserService userService;
    private final ListingHoldStore store;
    private final HoldProperties properties;

    public HoldService(ListingService listingService, UserService userService,
                       ListingHoldStore store, HoldProperties properties) {
        this.listingService = listingService;
        this.userService = userService;
        this.store = store;
        this.properties = properties;
    }

    /** @return результат і ознака, чи бронювання створене саме цим викликом */
    @Transactional(readOnly = true)
    public HoldResult hold(Long listingId, Long buyerId) {
        userService.get(buyerId);
        Listing listing = listingService.get(listingId);
        if (!listing.isActive()) {
            throw new ConflictException("Оголошення id=" + listingId + " неактивне (статус " + listing.getStatus() + ")");
        }
        if (listing.getSeller().getId().equals(buyerId)) {
            throw new BusinessRuleException("Не можна забронювати власний квиток");
        }
        if (listing.getEvent().hasStarted()) {
            throw new BusinessRuleException("Подія вже почалася — бронювання неможливе");
        }

        boolean created = store.tryAcquire(listingId, buyerId, properties.ttl());
        if (!created && !store.findHolder(listingId).map(buyerId::equals).orElse(false)) {
            throw new ConflictException("Оголошення тимчасово заброньоване іншим покупцем");
        }
        return new HoldResult(view(listingId, buyerId), created);
    }

    public HoldResponse get(Long listingId) {
        Long holder = store.findHolder(listingId)
                .orElseThrow(() -> new NotFoundException("Бронювання оголошення", listingId));
        return view(listingId, holder);
    }

    public void release(Long listingId, Long buyerId) {
        if (!store.release(listingId, buyerId)) {
            throw new NotFoundException("Бронювання цього покупця для оголошення", listingId);
        }
    }

    private HoldResponse view(Long listingId, Long buyerId) {
        return new HoldResponse(listingId, buyerId, store.secondsLeft(listingId));
    }

    public record HoldResult(HoldResponse hold, boolean created) {
    }
}
