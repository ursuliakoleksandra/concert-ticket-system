package com.ticketresale.listing;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public final class ListingDtos {

    private ListingDtos() {
    }

    public record CreateListingRequest(
            @NotNull @Positive Long ticketId,
            @NotNull @DecimalMin(value = "0.01") @Digits(integer = 8, fraction = 2) BigDecimal price) {
    }
}
