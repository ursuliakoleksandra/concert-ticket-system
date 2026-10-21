package com.ticketresale.ticket;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ticketresale.common.exception.BusinessRuleException;
import com.ticketresale.common.exception.NotFoundException;
import com.ticketresale.event.Event;
import com.ticketresale.event.EventService;
import com.ticketresale.ticket.TicketDtos.IssueTicketRequest;
import com.ticketresale.user.AppUser;
import com.ticketresale.user.UserService;

@Service
public class TicketService {

    private final TicketRepository tickets;
    private final EventService eventService;
    private final UserService userService;

    public TicketService(TicketRepository tickets, EventService eventService, UserService userService) {
        this.tickets = tickets;
        this.eventService = eventService;
        this.userService = userService;
    }

    @Transactional
    public Ticket issue(IssueTicketRequest r) {
        Event event = eventService.get(r.eventId());
        if (event.hasStarted()) {
            throw new BusinessRuleException("Не можна випустити квиток на подію, що вже почалася");
        }
        AppUser owner = userService.get(r.ownerId());
        // Унікальність (event_id, seat) гарантує БД → DataIntegrityViolation → 409
        return tickets.saveAndFlush(new Ticket(event, owner, r.seat().trim()));
    }

    @Transactional(readOnly = true)
    public Ticket get(Long id) {
        return tickets.findById(id).orElseThrow(() -> new NotFoundException("Квиток", id));
    }

    @Transactional(readOnly = true)
    public List<Ticket> findByOwner(Long ownerId) {
        userService.get(ownerId);
        return tickets.findAllByOwnerWithEvent(ownerId);
    }
}
