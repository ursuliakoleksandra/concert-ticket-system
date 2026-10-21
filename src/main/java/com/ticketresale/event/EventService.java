package com.ticketresale.event;

import java.time.Instant;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ticketresale.common.exception.ConflictException;
import com.ticketresale.common.exception.NotFoundException;
import com.ticketresale.event.EventDtos.EventRequest;
import com.ticketresale.ticket.TicketRepository;

@Service
public class EventService {

    private final EventRepository events;
    private final TicketRepository tickets;

    public EventService(EventRepository events, TicketRepository tickets) {
        this.events = events;
        this.tickets = tickets;
    }

    @Transactional
    public Event create(EventRequest r) {
        return events.save(new Event(r.title().trim(), r.venue().trim(), r.startsAt(), r.faceValue()));
    }

    @Transactional(readOnly = true)
    public Event get(Long id) {
        return events.findById(id).orElseThrow(() -> new NotFoundException("Подію", id));
    }

    @Transactional(readOnly = true)
    public Page<Event> listUpcoming(Pageable pageable) {
        return events.findByStartsAtAfter(Instant.now(), pageable);
    }

    @Transactional
    public Event update(Long id, EventRequest r) {
        Event event = get(id);
        event.update(r.title().trim(), r.venue().trim(), r.startsAt(), r.faceValue());
        return event;
    }

    @Transactional
    public void delete(Long id) {
        Event event = get(id);
        if (tickets.existsByEventId(id)) {
            throw new ConflictException("Неможливо видалити подію, для якої вже випущено квитки");
        }
        events.delete(event);
    }
}
