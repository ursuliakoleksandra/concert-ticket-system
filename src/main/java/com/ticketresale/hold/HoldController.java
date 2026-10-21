package com.ticketresale.hold;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ticketresale.hold.HoldService.HoldResult;

@RestController
@RequestMapping("/api/listings/{listingId}/hold")
public class HoldController {

    private final HoldService holdService;

    public HoldController(HoldService holdService) {
        this.holdService = holdService;
    }

    /** 201 — бронювання створено; 200 — бронювання цього покупця вже існує (ідемпотентність). */
    @PostMapping
    public ResponseEntity<HoldResponse> hold(@RequestHeader("X-User-Id") Long buyerId,
                                             @PathVariable Long listingId) {
        HoldResult result = holdService.hold(listingId, buyerId);
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK).body(result.hold());
    }

    @GetMapping
    public HoldResponse get(@PathVariable Long listingId) {
        return holdService.get(listingId);
    }

    @DeleteMapping
    public ResponseEntity<Void> release(@RequestHeader("X-User-Id") Long buyerId, @PathVariable Long listingId) {
        holdService.release(listingId, buyerId);
        return ResponseEntity.noContent().build();
    }
}
