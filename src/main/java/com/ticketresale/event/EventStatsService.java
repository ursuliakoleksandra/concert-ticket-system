package com.ticketresale.event;

import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketresale.cache.CacheResult;
import com.ticketresale.cache.StatsCache;
import com.ticketresale.event.EventDtos.EventStats;

/**
 * Статистика події через захищений кеш (ЛР8): популярна сторінка події під час старту продажу —
 * сотні одночасних читань одного ключа, а обчислення в СУБД коштовне.
 */
@Service
public class EventStatsService {

    private final StatsCache cache;
    private final EventStatsRepository repository;
    private final ObjectMapper mapper;

    public EventStatsService(StatsCache cache, EventStatsRepository repository, ObjectMapper mapper) {
        this.cache = cache;
        this.repository = repository;
        this.mapper = mapper;
    }

    /** @return статистика (null — подія не існує) і джерело відповіді для заголовка X-Cache */
    public CacheResult<EventStats> stats(Long eventId) {
        CacheResult<String> cached = cache.get(eventId, () -> repository.load(eventId).map(this::toJson));
        EventStats value = cached.value() == null ? null : fromJson(cached.value());
        return new CacheResult<>(value, cached.status());
    }

    private String toJson(EventStats stats) {
        try {
            return mapper.writeValueAsString(stats);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Не вдалося серіалізувати статистику", e);
        }
    }

    private EventStats fromJson(String json) {
        try {
            return mapper.readValue(json, EventStats.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Пошкоджений запис статистики в кеші", e);
        }
    }
}
