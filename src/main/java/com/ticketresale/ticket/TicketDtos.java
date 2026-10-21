package com.ticketresale.ticket;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public final class TicketDtos {

    private TicketDtos() {
    }

    /** Первинний випуск квитка (емуляція продажу організатором). */
    public record IssueTicketRequest(
            @NotNull @Positive Long eventId,
            @NotNull @Positive Long ownerId,
            @NotBlank @Size(max = 50) String seat) {
    }

    public record TicketResponse(Long id, Long eventId, String eventTitle, Long ownerId, String seat) {

        public static TicketResponse from(Ticket t) {
            return new TicketResponse(t.getId(), t.getEvent().getId(), t.getEvent().getTitle(),
                    t.getOwner().getId(), t.getSeat());
        }
    }
}
