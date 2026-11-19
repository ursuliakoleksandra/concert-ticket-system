package com.ticketresale.event;

import java.math.BigDecimal;
import java.time.Instant;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public final class EventDtos {

    private EventDtos() {
    }

    public record EventRequest(
            @NotBlank @Size(max = 200) String title,
            @NotBlank @Size(max = 200) String venue,
            @NotNull @Future Instant startsAt,
            @NotNull @DecimalMin(value = "0.01") @Digits(integer = 8, fraction = 2) BigDecimal faceValue) {
    }

    public record EventResponse(Long id, String title, String venue, Instant startsAt, BigDecimal faceValue) {

        public static EventResponse from(Event e) {
            return new EventResponse(e.getId(), e.getTitle(), e.getVenue(), e.getStartsAt(), e.getFaceValue());
        }
    }

    /**
     * Агрегована статистика події (ЛР8).
     *
     * @param computedAt момент обчислення в СУБД — показує вік закешованого значення
     */
    public record EventStats(Long eventId, String title, long activeListings, BigDecimal minPrice,
                             BigDecimal avgPrice, BigDecimal maxPrice, long soldTickets, BigDecimal revenue,
                             Instant computedAt) {
    }
}
