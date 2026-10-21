package com.ticketresale.purchase;

import java.net.URI;
import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PurchaseController {

    private final PurchaseService purchaseService;

    public PurchaseController(PurchaseService purchaseService) {
        this.purchaseService = purchaseService;
    }

    @PostMapping("/api/listings/{listingId}/purchase")
    public ResponseEntity<PurchaseResponse> purchase(@RequestHeader("X-User-Id") Long buyerId,
                                                     @PathVariable Long listingId) {
        PurchaseResponse body = PurchaseResponse.from(purchaseService.purchase(listingId, buyerId));
        return ResponseEntity.created(URI.create("/api/purchases/" + body.id())).body(body);
    }

    @GetMapping("/api/users/{userId}/purchases")
    public List<PurchaseResponse> byBuyer(@PathVariable Long userId) {
        return purchaseService.findByBuyer(userId).stream().map(PurchaseResponse::from).toList();
    }
}
