package com.ticketresale.event;

import java.net.URI;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ticketresale.cache.CacheResult;
import com.ticketresale.event.EventDtos.EventRequest;
import com.ticketresale.event.EventDtos.EventResponse;
import com.ticketresale.event.EventDtos.EventStats;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/events")
public class EventController {

    private final EventService eventService;
    private final EventStatsService statsService;

    public EventController(EventService eventService, EventStatsService statsService) {
        this.eventService = eventService;
        this.statsService = statsService;
    }

    @PostMapping
    public ResponseEntity<EventResponse> create(@Valid @RequestBody EventRequest request) {
        EventResponse body = EventResponse.from(eventService.create(request));
        return ResponseEntity.created(URI.create("/api/events/" + body.id())).body(body);
    }

    @GetMapping
    public Page<EventResponse> listUpcoming(
            @PageableDefault(size = 20, sort = "startsAt", direction = Sort.Direction.ASC) Pageable pageable) {
        return eventService.listUpcoming(pageable).map(EventResponse::from);
    }

    @GetMapping("/{id}")
    public EventResponse get(@PathVariable Long id) {
        return EventResponse.from(eventService.get(id));
    }

    /**
     * Агрегована статистика події (ЛР8). X-Cache: HIT / MISS / COALESCED / BYPASS.
     * Для неіснуючої події — 404; при Negative Caching повторні 404 не звертаються до СУБД.
     */
    @GetMapping("/{id}/stats")
    public ResponseEntity<Object> stats(@PathVariable Long id) {
        CacheResult<EventStats> result = statsService.stats(id);
        if (result.value() == null) {
            ProblemDetail notFound = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND,
                    "Подію з id=" + id + " не знайдено");
            notFound.setTitle("Ресурс не знайдено");
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .header("X-Cache", result.status().name())
                    .body(notFound);
        }
        return ResponseEntity.ok().header("X-Cache", result.status().name()).body(result.value());
    }

    @PutMapping("/{id}")
    public EventResponse update(@PathVariable Long id, @Valid @RequestBody EventRequest request) {
        return EventResponse.from(eventService.update(id, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        eventService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
