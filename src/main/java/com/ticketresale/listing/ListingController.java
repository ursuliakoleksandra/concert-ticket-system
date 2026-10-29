package com.ticketresale.listing;

import java.math.BigDecimal;
import java.net.URI;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.ticketresale.cache.CacheResult;
import com.ticketresale.listing.ListingDtos.CreateListingRequest;

import jakarta.validation.Valid;

/**
 * Заголовок X-User-Id — ідентифікатор поточного користувача.
 * У прототипі замінює автентифікацію (у наступних ЛР — JWT).
 */
@RestController
public class ListingController {

    static final String USER_HEADER = "X-User-Id";
    static final String CACHE_HEADER = "X-Cache";

    private final ListingService listingService;

    public ListingController(ListingService listingService) {
        this.listingService = listingService;
    }

    @PostMapping("/api/listings")
    public ResponseEntity<ListingResponse> create(@RequestHeader(USER_HEADER) Long userId,
                                                  @Valid @RequestBody CreateListingRequest request) {
        ListingResponse body = ListingResponse.from(listingService.create(userId, request));
        return ResponseEntity.created(URI.create("/api/listings/" + body.id())).body(body);
    }

    @GetMapping("/api/listings/{id}")
    public ListingResponse get(@PathVariable Long id) {
        return listingService.getView(id);
    }

    /** Кешований endpoint (ЛР4). Заголовок X-Cache: HIT / MISS / BYPASS / DISABLED — джерело даних. */
    @GetMapping("/api/events/{eventId}/listings")
    public ResponseEntity<Page<ListingResponse>> activeForEvent(
            @PathVariable Long eventId,
            @RequestParam(required = false) BigDecimal maxPrice,
            @PageableDefault(size = 20, sort = "price", direction = Sort.Direction.ASC) Pageable pageable) {
        CacheResult<Page<ListingResponse>> result = listingService.findActiveByEvent(eventId, maxPrice, pageable);
        return ResponseEntity.ok()
                .header(CACHE_HEADER, result.status().name())
                .body(result.value());
    }

    @DeleteMapping("/api/listings/{id}")
    public ResponseEntity<Void> cancel(@RequestHeader(USER_HEADER) Long userId, @PathVariable Long id) {
        listingService.cancel(id, userId);
        return ResponseEntity.noContent().build();
    }
}
