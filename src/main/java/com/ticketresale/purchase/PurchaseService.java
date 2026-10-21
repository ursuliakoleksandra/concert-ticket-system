package com.ticketresale.purchase;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.ticketresale.common.ResaleRules;
import com.ticketresale.common.exception.BusinessRuleException;
import com.ticketresale.common.exception.ConflictException;
import com.ticketresale.common.exception.NotFoundException;
import com.ticketresale.hold.ListingHoldStore;
import com.ticketresale.listing.Listing;
import com.ticketresale.listing.ListingRepository;
import com.ticketresale.user.AppUser;
import com.ticketresale.user.UserRepository;
import com.ticketresale.user.UserService;

/**
 * Пікова операція системи (High-Load Scenario): купівля квитка з перепродажу.
 * <p>
 * Увесь стан, потрібний для рішення, читається зі спільних сховищ (Redis — бронювання,
 * PostgreSQL — оголошення, ліміти), а зміни фіксуються однією ACID-транзакцією.
 * Блокування беруться в PostgreSQL, а не в пам'яті JVM, тому діють для всіх екземплярів.
 * Порядок блокувань завжди однаковий (покупець → оголошення), що виключає взаємоблокування.
 */
@Service
public class PurchaseService {

    private final ListingRepository listings;
    private final PurchaseRepository purchases;
    private final UserRepository users;
    private final UserService userService;
    private final ListingHoldStore holds;
    private final ResaleRules rules;

    public PurchaseService(ListingRepository listings, PurchaseRepository purchases, UserRepository users,
                           UserService userService, ListingHoldStore holds, ResaleRules rules) {
        this.listings = listings;
        this.purchases = purchases;
        this.users = users;
        this.userService = userService;
        this.holds = holds;
        this.rules = rules;
    }

    @Transactional
    public Purchase purchase(Long listingId, Long buyerId) {
        // 1. Бронювання в Redis: якщо оголошення утримує інший покупець — відмова без звернення до блокувань БД
        holds.findHolder(listingId)
                .filter(holder -> !holder.equals(buyerId))
                .ifPresent(holder -> {
                    throw new ConflictException("Оголошення тимчасово заброньоване іншим покупцем");
                });

        // 2. Блокування рядка покупця: серіалізує його паралельні покупки (ліміт квитків на подію)
        AppUser buyer = users.findByIdForUpdate(buyerId)
                .orElseThrow(() -> new NotFoundException("Користувача", buyerId));

        // 3. Блокування рядка оголошення: паралельні покупці того ж оголошення чекають тут
        Listing listing = listings.findByIdForUpdate(listingId)
                .orElseThrow(() -> new NotFoundException("Оголошення", listingId));

        // 4. Перевірки бізнес-правил
        if (listing.getSeller().getId().equals(buyerId)) {
            throw new BusinessRuleException("Не можна купити власний квиток");
        }
        if (listing.getEvent().hasStarted()) {
            throw new BusinessRuleException("Подія вже почалася — купівля неможлива");
        }
        long alreadyBought = purchases.countByBuyerIdAndEventId(buyerId, listing.getEvent().getId());
        if (alreadyBought >= rules.maxTicketsPerEvent()) {
            throw new BusinessRuleException("Досягнуто ліміт " + rules.maxTicketsPerEvent()
                    + " квитків на одну подію для одного покупця");
        }

        // 5. Зміна стану: оголошення → SOLD (409, якщо вже неактивне), квиток → новому власнику
        listing.markSold();
        listing.getTicket().transferTo(buyer);
        Purchase purchase = purchases.save(new Purchase(listing, buyerId));

        // 6. Бронювання знімається лише після успішного COMMIT
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                holds.forceRelease(listingId);
            }
        });
        return purchase;
    }

    @Transactional(readOnly = true)
    public List<Purchase> findByBuyer(Long buyerId) {
        userService.get(buyerId);
        return purchases.findByBuyerIdOrderByCreatedAtDesc(buyerId);
    }
}
