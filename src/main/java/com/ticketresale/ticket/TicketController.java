package com.ticketresale.ticket;

import java.net.URI;
import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.ticketresale.ticket.TicketDtos.IssueTicketRequest;
import com.ticketresale.ticket.TicketDtos.TicketResponse;

import jakarta.validation.Valid;

@RestController
public class TicketController {

    private final TicketService ticketService;

    public TicketController(TicketService ticketService) {
        this.ticketService = ticketService;
    }

    @PostMapping("/api/tickets")
    public ResponseEntity<TicketResponse> issue(@Valid @RequestBody IssueTicketRequest request) {
        TicketResponse body = TicketResponse.from(ticketService.issue(request));
        return ResponseEntity.created(URI.create("/api/tickets/" + body.id())).body(body);
    }

    @GetMapping("/api/users/{userId}/tickets")
    public List<TicketResponse> ownedBy(@PathVariable Long userId) {
        return ticketService.findByOwner(userId).stream().map(TicketResponse::from).toList();
    }
}
